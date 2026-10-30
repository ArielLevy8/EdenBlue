// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.features.settings.model.ShortSetting
import org.yuzu.yuzu_emu.model.DriverViewModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Process-wide flags used by the EdenBlue home menu boot flow. */
object EdenBlueState {
    /** Set once the home menu has been auto-started for this process. */
    @Volatile
    var homeMenuAutoBooted = false

    /** Set by the emulation activity when a game exits and the home menu should come back. */
    @Volatile
    var pendingHomeMenuRelaunch = false
}

/**
 * Rule-based tuning: picks a settings preset and GPU driver from the chip and GPU.
 * Presets are starting points grouped by GPU generation, so they can be changed freely afterwards.
 */
object DeviceTuning {
    private const val PREF_APPLIED = "EdenBlueDeviceTuningApplied"

    private const val PURPLE_TURNIP = "MrPurple666/purple-turnip"
    private const val KIMCHI_DRIVERS = "K11MCH1/AdrenoToolsDrivers"
    private const val GAMEHUB_DRIVERS = "crueter/GameHub-8Elite-Drivers"

    data class Chip(val name: String, val adreno: Int, val socCodes: Set<String>) {
        val label: String get() = "$name (Adreno $adreno)"
    }

    enum class Tier(
        val resolution: Int,
        val scalingFilter: Int,
        val anisotropy: Int,
        val vramMode: Int,
        val dynamicState: Int,
        val vertexInputDynamicState: Boolean,
        val pipelineWorkers: Int
    ) {
        ENTRY(RES_1X, SCALING_BILINEAR, ANISOTROPY_DEFAULT, VRAM_CONSERVATIVE, EDS2, false, 3),
        MID(RES_1X, SCALING_FSR, ANISOTROPY_2X, VRAM_CONSERVATIVE, EDS2, false, 4),
        HIGH(RES_3_2X, SCALING_FSR, ANISOTROPY_4X, VRAM_AGGRESSIVE, EDS3, true, 4),
        TOP(RES_2X, SCALING_FSR, ANISOTROPY_4X, VRAM_AGGRESSIVE, EDS3, true, 5);
    }

    data class DriverSource(val repository: String, val assetHint: String)

    data class DriverRelease(val name: String, val url: String)

    val chips: List<Chip> = listOf(
        Chip("Snapdragon 865 / 865+", 650, setOf("SM8250")),
        Chip("Snapdragon 870", 650, setOf("SM8250-AC")),
        Chip("Snapdragon 888 / 888+", 660, setOf("SM8350", "SM8350-AC")),
        Chip("Snapdragon 7+ Gen 2", 725, setOf("SM7475", "SM7475-AB")),
        Chip("Snapdragon 8 Gen 1", 730, setOf("SM8450")),
        Chip("Snapdragon 8+ Gen 1", 730, setOf("SM8475")),
        Chip("Snapdragon 7+ Gen 3", 732, setOf("SM7675")),
        Chip("Snapdragon 8s Gen 3", 735, setOf("SM8635")),
        Chip("Snapdragon 8 Gen 2", 740, setOf("SM8550", "SM8550-AC")),
        Chip("Snapdragon 8 Gen 3", 750, setOf("SM8650", "SM8650-AC")),
        Chip("Snapdragon 8s Gen 4", 825, setOf("SM8735")),
        Chip("Snapdragon 8 Elite", 830, setOf("SM8750", "SM8750-AB")),
        Chip("Snapdragon 8 Elite Gen 5", 840, setOf("SM8850"))
    )

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    fun isApplied(): Boolean = preferences.getBoolean(PREF_APPLIED, false)

    fun tierFor(adreno: Int): Tier = when (adreno) {
        in 800..Int.MAX_VALUE -> Tier.TOP
        in 732..799 -> Tier.HIGH
        in 700..731 -> Tier.MID
        else -> Tier.ENTRY
    }

    /** Matches the per-GPU driver recommendations of the Eden driver fetcher. */
    fun driverFor(adreno: Int): DriverSource? = when (adreno) {
        in 640..699 -> DriverSource(PURPLE_TURNIP, "T19")
        in 700..710 -> DriverSource(KIMCHI_DRIVERS, "v25.2.0_R5_auto")
        in 711..799 -> DriverSource(PURPLE_TURNIP, "T23")
        in 800..899 -> DriverSource(GAMEHUB_DRIVERS, "")
        else -> null
    }

    /** Finds this device in [chips] from the GPU name, falling back to the SoC model code. */
    fun detectChip(gpuModel: String?): Chip? {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        chips.firstOrNull { socModel.uppercase() in it.socCodes }?.let { return it }

        val adreno = gpuModel?.let { Regex("Adreno\\D*(\\d{3})").find(it) }
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return chips.firstOrNull { it.adreno == adreno }
    }

    /** Writes the preset for [chip] into the global config and saves it. */
    fun apply(chip: Chip) {
        val tier = tierFor(chip.adreno)

        BooleanSetting.USE_DOCKED_MODE.setBoolean(false)
        IntSetting.RENDERER_RESOLUTION.setInt(tier.resolution)
        IntSetting.RENDERER_VSYNC.setInt(VSYNC_IMMEDIATE)
        IntSetting.RENDERER_SCALING_FILTER.setInt(tier.scalingFilter)
        IntSetting.RENDERER_ANTI_ALIASING.setInt(AA_NONE)
        IntSetting.MAX_ANISOTROPY.setInt(tier.anisotropy)
        IntSetting.RENDERER_ACCURACY.setInt(GPU_ACCURACY_LOW)
        IntSetting.DMA_ACCURACY.setInt(DMA_DEFAULT)
        IntSetting.RENDERER_VRAM_USAGE_MODE.setInt(tier.vramMode)
        IntSetting.RENDERER_ASTC_DECODE_METHOD.setInt(ASTC_GPU)
        IntSetting.RENDERER_NVDEC_EMULATION.setInt(NVDEC_CPU)
        BooleanSetting.RENDERER_USE_DISK_SHADER_CACHE.setBoolean(true)
        BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS.setBoolean(true)
        BooleanSetting.RENDERER_ASYNCHRONOUS_GPU_EMULATION.setBoolean(true)
        BooleanSetting.RENDERER_REACTIVE_FLUSHING.setBoolean(false)
        BooleanSetting.RENDERER_FORCE_MAX_CLOCK.setBoolean(false)
        IntSetting.RENDERER_DYNA_STATE.setInt(tier.dynamicState)
        BooleanSetting.RENDERER_VERTEX_INPUT_DYNAMIC_STATE.setBoolean(tier.vertexInputDynamicState)
        BooleanSetting.RENDERER_SAMPLE_SHADING.setBoolean(false)
        IntSetting.ANDROID_PIPELINE_WORKERS.setInt(tier.pipelineWorkers)

        IntSetting.CPU_ACCURACY.setInt(CPU_ACCURACY_AUTO)
        BooleanSetting.FASTMEM.setBoolean(true)
        IntSetting.MEMORY_LAYOUT.setInt(MEMORY_4GB)
        BooleanSetting.CORE_SYNC_CORE_SPEED.setBoolean(false)
        BooleanSetting.RENDERER_USE_SPEED_LIMIT.setBoolean(true)
        ShortSetting.RENDERER_SPEED_LIMIT.setShort(100.toShort())

        NativeConfig.saveGlobalConfig()
        preferences.edit { putBoolean(PREF_APPLIED, true) }
    }

    /** Finds the release for [source]. An empty hint means the newest package. */
    @Throws(IOException::class)
    fun findDriver(client: OkHttpClient, source: DriverSource): DriverRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/" + source.repository + "/releases?per_page=100")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code)
            }
            response.body?.string() ?: throw IOException("Empty response")
        }

        val root = jacksonObjectMapper().readTree(body)
        if (root.isArray) {
            for (node in root) {
                val assets = node.get("assets") ?: continue
                for (asset in assets) {
                    val name = asset.get("name")?.asText() ?: continue
                    if (!name.endsWith(".zip", ignoreCase = true)) continue
                    if (!name.contains(source.assetHint, ignoreCase = true)) continue
                    val url = asset.get("browser_download_url")?.asText() ?: continue
                    return DriverRelease(name, url)
                }
            }
        }
        throw IOException("No driver matching " + source.assetHint + " in " + source.repository)
    }

    /** Downloads [release], installs it into the driver store and selects it. Returns the driver name. */
    @Throws(IOException::class)
    fun downloadAndInstallDriver(
        context: Context,
        client: OkHttpClient,
        release: DriverRelease,
        driverViewModel: DriverViewModel
    ): String {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        cacheDir.mkdirs()
        val file = File(cacheDir, release.name)

        val request = Request.Builder()
            .url(release.url)
            .header("Accept", "application/octet-stream")
            .build()
        client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP " + response.code)
                }
                val stream = response.body?.byteStream() ?: throw IOException("Empty response body")
                stream.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
            }
        if (file.length() == 0L) {
            throw IOException("Downloaded file is empty")
        }

        val metadata = GpuDriverHelper.getMetadataFromZip(file)
        val driverPath = GpuDriverHelper.driverStoragePath + FileUtil.getFilename(file.toUri())
        if (!GpuDriverHelper.copyDriverToInternalStorage(file.toUri())) {
            throw IOException("Failed to copy driver into internal storage")
        }
        driverViewModel.onDriverAdded(Pair(driverPath, metadata))
        NativeConfig.saveGlobalConfig()
        file.delete()
        return metadata.name ?: release.name
    }
}

// Values mirror the enum order in src/common/settings_enums.h
private const val RES_1X = 3
private const val RES_3_2X = 5
private const val RES_2X = 6
private const val VSYNC_IMMEDIATE = 0
private const val SCALING_BILINEAR = 1
private const val SCALING_FSR = 6
private const val AA_NONE = 0
private const val ANISOTROPY_DEFAULT = 1
private const val ANISOTROPY_2X = 2
private const val ANISOTROPY_4X = 3
private const val GPU_ACCURACY_LOW = 0
private const val DMA_DEFAULT = 0
private const val VRAM_CONSERVATIVE = 0
private const val VRAM_AGGRESSIVE = 1
private const val ASTC_GPU = 1
private const val NVDEC_CPU = 1
private const val EDS2 = 2
private const val EDS3 = 3
private const val CPU_ACCURACY_AUTO = 0
private const val MEMORY_4GB = 0
