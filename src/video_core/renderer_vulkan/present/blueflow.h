// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "common/common_types.h"
#include "video_core/renderer_vulkan/present/lsfg_common.h"

namespace Vulkan {

class Device;

/// Built-in frame interpolation that needs no external shader DLL.
class BlueFlowChain {
public:
    BlueFlowChain(const Device& device, MemoryAllocator& memory_allocator, VkExtent2D extent,
                  VkFormat format);

    BlueFlowChain(const BlueFlowChain&) = delete;
    BlueFlowChain& operator=(const BlueFlowChain&) = delete;

    [[nodiscard]] static bool IsSupported(const Device& device);

    [[nodiscard]] LsfgImage& Input(u64 frame_count) {
        return frames[frame_count % frames.size()];
    }

    void DispatchShared(vk::CommandBuffer cmdbuf, u64 frame_count);

    void SetTarget(const Device& device, size_t generation_count, size_t generation, u32 target,
                   VkImageView view);

    void DispatchGeneration(vk::CommandBuffer cmdbuf, u64 frame_count, size_t generation_count,
                            size_t generation, u32 target, VkImage image, VkExtent2D extent);

private:
    struct Target {
        std::array<VkDescriptorSet, 2> descriptor_sets{};
        VkImageView view{};
    };

    struct Generation {
        std::array<Target, LSFG_MAX_TARGETS> targets{};
        VkBuffer buffer{};
    };

    LsfgResources resources;
    vk::DescriptorPool descriptor_pool;
    vk::ShaderModule motion_shader;
    vk::ShaderModule generate_shader;
    vk::Sampler sampler;

    LsfgImagePair frames;
    LsfgImagePair motion;

    LsfgPass motion_pass;
    LsfgPass generate_pass;

    std::array<VkDescriptorSet, 2> motion_sets{};
    std::array<Generation, LSFG_GENERATION_SLOTS> generations{};
    vk::DescriptorSets owned_motion_sets;
    vk::DescriptorSets owned_generate_sets;

    bool motion_cleared{};
};

} // namespace Vulkan
