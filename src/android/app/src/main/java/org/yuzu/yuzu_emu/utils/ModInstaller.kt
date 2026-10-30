// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import org.yuzu.yuzu_emu.YuzuApplication
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Installs mod ZIPs into the load directory. */
object ModInstaller {
    private const val MAX_SEARCH_DEPTH = 4

    private val modRootMarkers = AddonUtil.validAddonDirectories

    fun loadDirectory(titleIdHex: String): File =
        File(DirectoryInitialization.userDirectory + "/load/" + titleIdHex.uppercase())

    /**
     * Installs every mod found inside [zip] into load/[titleIdHex]/.
     * @return the number of mod folders installed, 0 if the ZIP contained no mod.
     */
    @Throws(IOException::class, SecurityException::class)
    fun installZip(
        zip: File,
        titleIdHex: String,
        progressCallback: (max: Long, progress: Long) -> Boolean = { _, _ -> false }
    ): Int {
        val tempDir = File(YuzuApplication.appContext.cacheDir, "mod_extract_${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            extract(zip, tempDir, progressCallback)

            val modRoots = findModRoots(tempDir)
            if (modRoots.isEmpty()) {
                return 0
            }

            val loadDir = loadDirectory(titleIdHex)
            loadDir.mkdirs()
            val defaultName = zip.name.removeSuffix(".zip").removeSuffix(".ZIP")
            modRoots.forEach { root ->
                val name = if (root == tempDir) defaultName else root.name
                val target = File(loadDir, sanitize(name))
                if (target.exists()) {
                    target.deleteRecursively()
                }
                if (!root.copyRecursively(target, overwrite = true)) {
                    throw IOException("Failed to copy mod into " + target.absolutePath)
                }
            }
            return modRoots.size
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun extract(
        zip: File,
        destDir: File,
        progressCallback: (max: Long, progress: Long) -> Boolean
    ) {
        var totalEntries = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (zis.nextEntry != null) {
                totalEntries++
            }
        }

        var progress = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (progressCallback(totalEntries, progress)) {
                    return
                }
                val newFile = File(destDir, entry.name)
                if (!newFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw SecurityException("Zip file attempted path traversal! " + entry.name)
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    newFile.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
                progress++
            }
        }
    }

    /** Finds directories that look like a mod root (contain exefs/, romfs/ or cheats/). */
    private fun findModRoots(dir: File, depth: Int = 0): List<File> {
        if (isModRoot(dir)) {
            return listOf(dir)
        }
        if (depth >= MAX_SEARCH_DEPTH) {
            return emptyList()
        }
        val children = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        return children.flatMap { findModRoots(it, depth + 1) }
    }

    private fun isModRoot(dir: File): Boolean =
        dir.listFiles()?.any { it.isDirectory && modRootMarkers.contains(it.name.lowercase()) } == true

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifEmpty { "mod" }
    }
}
