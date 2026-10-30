// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <vector>

#include "video_core/host_shaders/vulkan_blueflow_generate_comp_spv.h"
#include "video_core/host_shaders/vulkan_blueflow_motion_comp_spv.h"
#include "video_core/renderer_vulkan/present/blueflow.h"
#include "video_core/renderer_vulkan/present/util.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

namespace {

constexpr u32 MOTION_BLOCK_SIZE = 8;
constexpr u32 MOTION_GROUP_SIZE = 8;
constexpr u32 GENERATE_GROUP_SIZE = 16;
constexpr u32 MOTION_DESCRIPTOR_SETS = 2;
constexpr u32 GENERATE_DESCRIPTOR_SETS =
    static_cast<u32>(LSFG_GENERATION_SLOTS * LSFG_MAX_TARGETS * 2);

[[nodiscard]] u32 GroupCount(u32 size, u32 group_size) {
    return (size + group_size - 1) / group_size;
}

[[nodiscard]] VkExtent2D MotionExtent(VkExtent2D extent) {
    return VkExtent2D{.width = GroupCount(extent.width, MOTION_BLOCK_SIZE),
                      .height = GroupCount(extent.height, MOTION_BLOCK_SIZE)};
}

VkImageMemoryBarrier MakeClearedBarrier(VkImage image) {
    return VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
}

VkImageMemoryBarrier MakeTargetBarrier(VkImage image) {
    return VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
}

} // Anonymous namespace

bool BlueFlowChain::IsSupported(const Device& device) {
    return device.IsFormatlessImageStoreSupported();
}

BlueFlowChain::BlueFlowChain(const Device& device, MemoryAllocator& memory_allocator,
                             VkExtent2D extent, VkFormat format)
    : resources{device, memory_allocator, 1.0f},
      descriptor_pool{CreateLsfgDescriptorPool(device, MOTION_DESCRIPTOR_SETS +
                                                           GENERATE_DESCRIPTOR_SETS)},
      motion_shader{CreateWrappedShaderModule(device, VULKAN_BLUEFLOW_MOTION_COMP_SPV)},
      generate_shader{CreateWrappedShaderModule(device, VULKAN_BLUEFLOW_GENERATE_COMP_SPV)},
      sampler{CreateWrappedSampler(device, VK_FILTER_LINEAR)} {
    for (auto& image : frames) {
        image = LsfgImage(device, memory_allocator, extent, format);
    }
    for (auto& image : motion) {
        image = LsfgImage(device, memory_allocator, MotionExtent(extent), LSFG_MOTION_FORMAT);
    }

    motion_pass = LsfgPass(device, *motion_shader,
                           {{1, VK_DESCRIPTOR_TYPE_SAMPLER},
                            {3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                            {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});
    generate_pass = LsfgPass(device, *generate_shader,
                             {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                              {1, VK_DESCRIPTOR_TYPE_SAMPLER},
                              {3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE},
                              {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}});

    const std::vector<VkDescriptorSetLayout> motion_layouts(MOTION_DESCRIPTOR_SETS,
                                                            motion_pass.SetLayout());
    owned_motion_sets = CreateWrappedDescriptorSets(descriptor_pool, motion_layouts);
    for (size_t i = 0; i < motion_sets.size(); ++i) {
        motion_sets[i] = owned_motion_sets[i];
        LsfgDescriptorWriter(motion_sets[i])
            .AddSampler(*sampler)
            .AddSampledImage(frames[1 - i])
            .AddSampledImage(frames[i])
            .AddSampledImage(motion[1 - i])
            .AddStorageImage(motion[i])
            .Build(device);
    }

    const std::vector<VkDescriptorSetLayout> generate_layouts(GENERATE_DESCRIPTOR_SETS,
                                                              generate_pass.SetLayout());
    owned_generate_sets = CreateWrappedDescriptorSets(descriptor_pool, generate_layouts);

    size_t next = 0;
    for (size_t slot = 0; slot < LSFG_GENERATION_SLOTS; ++slot) {
        Generation& generation = generations[slot];
        generation.buffer = resources.GetBuffer(LsfgSlotTimestamp(slot));
        for (auto& entry : generation.targets) {
            for (auto& set : entry.descriptor_sets) {
                set = owned_generate_sets[next++];
            }
        }
    }
}

void BlueFlowChain::DispatchShared(vk::CommandBuffer cmdbuf, u64 frame_count) {
    if (!motion_cleared) {
        for (auto& image : motion) {
            ClearColorImage(cmdbuf, image.Handle());
            image.SetLayout(VK_IMAGE_LAYOUT_GENERAL);
        }
        const std::array barriers{MakeClearedBarrier(motion[0].Handle()),
                                  MakeClearedBarrier(motion[1].Handle())};
        cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                               0, {}, {}, barriers);
        motion_cleared = true;
    }

    const size_t index = frame_count % motion.size();
    LsfgBarriers(cmdbuf)
        .WriteToReadAll(frames)
        .WriteToRead(motion[1 - index])
        .ReadToWrite(motion[index])
        .Build();

    motion_pass.Bind(cmdbuf, motion_sets[index]);
    const VkExtent2D extent = motion[index].Extent();
    cmdbuf.Dispatch(GroupCount(extent.width, MOTION_GROUP_SIZE),
                    GroupCount(extent.height, MOTION_GROUP_SIZE), 1);
}

void BlueFlowChain::SetTarget(const Device& device, size_t generation_count, size_t generation,
                              u32 target, VkImageView view) {
    const size_t slot = LsfgGenerationSlot(generation_count, generation);
    Target& entry = generations[slot].targets[target];
    if (entry.view == view) {
        return;
    }
    entry.view = view;

    for (size_t i = 0; i < entry.descriptor_sets.size(); ++i) {
        LsfgDescriptorWriter(entry.descriptor_sets[i])
            .AddUniformBuffer(generations[slot].buffer, LsfgResources::BufferSize())
            .AddSampler(*sampler)
            .AddSampledImage(frames[1 - i])
            .AddSampledImage(frames[i])
            .AddSampledImage(motion[i])
            .AddStorageView(view)
            .Build(device);
    }
}

void BlueFlowChain::DispatchGeneration(vk::CommandBuffer cmdbuf, u64 frame_count,
                                       size_t generation_count, size_t generation, u32 target,
                                       VkImage image, VkExtent2D extent) {
    const size_t slot = LsfgGenerationSlot(generation_count, generation);
    const size_t index = frame_count % motion.size();
    const Target& entry = generations[slot].targets[target];

    LsfgBarriers(cmdbuf)
        .WriteToReadAll(frames)
        .WriteToRead(motion[index])
        .DiscardToWrite(image)
        .Build();

    generate_pass.Bind(cmdbuf, entry.descriptor_sets[index]);
    cmdbuf.Dispatch(GroupCount(extent.width, GENERATE_GROUP_SIZE),
                    GroupCount(extent.height, GENERATE_GROUP_SIZE), 1);

    const std::array after{MakeTargetBarrier(image)};
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_TRANSFER_BIT,
                           0, {}, {}, after);
}

} // namespace Vulkan
