// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.YuzuApplication
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Moves game files the user already has on the device into their ROMs folder. Accepts .nsp and
 * .xci files, or ZIP archives containing them. An original is only removed after its games were
 * copied in full.
 */
object GameImporter {
    private const val PREF_DESTINATION = "EdenBlueImportDestination"
    private const val BINARY_MIME = "application/octet-stream"

    private val gameExtensions = setOf("nsp", "xci")

    data class Result(
        val imported: Int,
        val alreadyPresent: Int,
        val originalsRemoved: Int,
        val rejected: List<String>
    )

    private val context get() = YuzuApplication.appContext

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    /** The saved ROMs folder, if this app can still write to it. */
    fun destination(): Uri? {
        val uri = preferences.getString(PREF_DESTINATION, null)?.toUri() ?: return null
        val writable = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (writable) uri else null
    }

    fun saveDestination(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        preferences.edit { putString(PREF_DESTINATION, uri.toString()) }
    }

    fun import(
        sources: List<Uri>,
        destination: Uri,
        removeOriginals: Boolean,
        progressCallback: (max: Long, progress: Long) -> Boolean = { _, _ -> false }
    ): Result {
        val folder = DocumentFile.fromTreeUri(context, destination)
            ?: throw IOException("Cannot open the ROMs folder")

        var imported = 0
        var alreadyPresent = 0
        var removed = 0
        val rejected = mutableListOf<String>()

        sources.forEachIndexed { index, source ->
            if (progressCallback(sources.size.toLong(), index.toLong())) {
                return Result(imported, alreadyPresent, removed, rejected)
            }

            val name = FileUtil.getFilename(source)
            val extension = name.substringAfterLast('.', "").lowercase()
            val outcome = try {
                when {
                    extension in gameExtensions -> importGame(source, name, folder)
                    extension == "zip" -> importArchive(source, folder)
                    else -> Outcome()
                }
            } catch (e: IOException) {
                Log.warning("[GameImporter] " + name + ": " + e.message)
                Outcome()
            }

            imported += outcome.copied
            alreadyPresent += outcome.present
            if (outcome.copied + outcome.present == 0) {
                rejected.add(name)
            } else if (removeOriginals && deleteOriginal(source)) {
                removed++
            }
        }
        return Result(imported, alreadyPresent, removed, rejected)
    }

    private data class Outcome(val copied: Int = 0, val present: Int = 0)

    private fun importGame(source: Uri, name: String, folder: DocumentFile): Outcome {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Cannot open " + name)
        return input.use { copyInto(it, name, folder) }
    }

    private fun importArchive(source: Uri, folder: DocumentFile): Outcome {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Cannot open archive")

        var result = Outcome()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // Only the bare file name is used, so entries cannot escape the ROMs folder.
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                val extension = name.substringAfterLast('.', "").lowercase()
                if (!entry.isDirectory && extension in gameExtensions) {
                    val copied = copyInto(zip, name, folder)
                    result = Outcome(
                        result.copied + copied.copied,
                        result.present + copied.present
                    )
                }
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun copyInto(input: InputStream, name: String, folder: DocumentFile): Outcome {
        if (folder.findFile(name) != null) {
            return Outcome(present = 1)
        }

        val target = folder.createFile(BINARY_MIME, name)
            ?: throw IOException("Cannot create " + name)
        try {
            val output = context.contentResolver.openOutputStream(target.uri)
                ?: throw IOException("Cannot write " + name)
            output.use { input.copyTo(it) }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return Outcome(copied = 1)
    }

    private fun deleteOriginal(source: Uri): Boolean = try {
        DocumentFile.fromSingleUri(context, source)?.delete() == true
    } catch (e: SecurityException) {
        Log.warning("[GameImporter] Could not remove original: " + e.message)
        false
    }
}
