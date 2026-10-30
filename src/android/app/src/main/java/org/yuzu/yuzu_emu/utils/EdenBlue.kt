// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
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
 * Tuned settings profile for Snapdragon 865 devices with the Adreno 650 GPU
 * (Retroid Pocket 5 / Flip 2, OnePlus 8 series, Galaxy S20 and similar).
 */
object Sd865Optimizer {
    private const val PREF_APPLIED = "EdenBlueSd865ProfileApplied"

    const val RECOMMENDED_DRIVER_REPO = "MrPurple666/purple-turnip"
    private const val RECOMMENDED_DRIVER_HINT = "T19"

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    fun isApplied(): Boolean = preferences.getBoolean(PREF_APPLIED, false)

    /** Writes the profile into the global config and saves it. */
    fun apply() {
        // Display and renderer
        BooleanSetting.USE_DOCKED_MODE.setBoolean(false)
        IntSetting.RENDERER_RESOLUTION.setInt(RESOLUTION_1X)
        IntSetting.RENDERER_VSYNC.setInt(VSYNC_IMMEDIATE)
        IntSetting.RENDERER_SCALING_FILTER.setInt(SCALING_BILINEAR)
        IntSetting.RENDERER_ANTI_ALIASING.setInt(AA_NONE)
        IntSetting.MAX_ANISOTROPY.setInt(ANISOTROPY_DEFAULT)
        IntSetting.RENDERER_ACCURACY.setInt(GPU_ACCURACY_LOW)
        IntSetting.DMA_ACCURACY.setInt(DMA_DEFAULT)
        IntSetting.RENDERER_VRAM_USAGE_MODE.setInt(VRAM_CONSERVATIVE)
        IntSetting.RENDERER_ASTC_DECODE_METHOD.setInt(ASTC_GPU)
        IntSetting.RENDERER_NVDEC_EMULATION.setInt(NVDEC_CPU)
        BooleanSetting.RENDERER_USE_DISK_SHADER_CACHE.setBoolean(true)
        BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS.setBoolean(true)
        BooleanSetting.RENDERER_ASYNCHRONOUS_GPU_EMULATION.setBoolean(true)
        BooleanSetting.RENDERER_REACTIVE_FLUSHING.setBoolean(false)
        BooleanSetting.RENDERER_FORCE_MAX_CLOCK.setBoolean(false)
        IntSetting.RENDERER_DYNA_STATE.setInt(DYNA_STATE_EDS2)
        BooleanSetting.RENDERER_VERTEX_INPUT_DYNAMIC_STATE.setBoolean(false)
        BooleanSetting.RENDERER_SAMPLE_SHADING.setBoolean(false)
        IntSetting.ANDROID_PIPELINE_WORKERS.setInt(PIPELINE_WORKERS)

        // CPU and system
        IntSetting.CPU_ACCURACY.setInt(CPU_ACCURACY_AUTO)
        BooleanSetting.FASTMEM.setBoolean(true)
        IntSetting.MEMORY_LAYOUT.setInt(MEMORY_4GB)
        BooleanSetting.CORE_SYNC_CORE_SPEED.setBoolean(false)
        BooleanSetting.RENDERER_USE_SPEED_LIMIT.setBoolean(true)
        ShortSetting.RENDERER_SPEED_LIMIT.setShort(100.toShort())

        NativeConfig.saveGlobalConfig()
        preferences.edit { putBoolean(PREF_APPLIED, true) }
    }

    data class DriverRelease(val name: String, val url: String)

    /** Finds the Mr. Purple Turnip build recommended for the Adreno 650, falling back to the newest. */
    @Throws(IOException::class)
    fun findRecommendedDriver(client: OkHttpClient): DriverRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$RECOMMENDED_DRIVER_REPO/releases?per_page=100")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code)
            }
            response.body?.string() ?: throw IOException("Empty response")
        }

        val root = jacksonObjectMapper().readTree(body)
        var fallback: DriverRelease? = null
        if (root.isArray) {
            for (node in root) {
                val assets = node.get("assets") ?: continue
                for (asset in assets) {
                    val name = asset.get("name")?.asText() ?: continue
                    if (!name.endsWith(".zip", ignoreCase = true)) continue
                    val url = asset.get("browser_download_url")?.asText() ?: continue
                    val release = DriverRelease(name, url)
                    if (name.contains(RECOMMENDED_DRIVER_HINT, ignoreCase = true)) {
                        return release
                    }
                    if (fallback == null) {
                        fallback = release
                    }
                }
            }
        }
        return fallback ?: throw IOException("No driver packages found")
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

    // Values mirror the enum order in src/common/settings_enums.h
    private const val RESOLUTION_1X = 3
    private const val VSYNC_IMMEDIATE = 0
    private const val SCALING_BILINEAR = 1
    private const val AA_NONE = 0
    private const val ANISOTROPY_DEFAULT = 1
    private const val GPU_ACCURACY_LOW = 0
    private const val DMA_DEFAULT = 0
    private const val VRAM_CONSERVATIVE = 0
    private const val ASTC_GPU = 1
    private const val NVDEC_CPU = 1
    private const val DYNA_STATE_EDS2 = 2
    private const val PIPELINE_WORKERS = 3
    private const val CPU_ACCURACY_AUTO = 0
    private const val MEMORY_4GB = 0
}
