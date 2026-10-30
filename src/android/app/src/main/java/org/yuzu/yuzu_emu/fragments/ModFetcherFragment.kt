// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentModFetcherBinding
import org.yuzu.yuzu_emu.features.fetcher.ModPackage
import org.yuzu.yuzu_emu.features.fetcher.ModSource
import org.yuzu.yuzu_emu.features.fetcher.ModSourceAdapter
import org.yuzu.yuzu_emu.model.AddonViewModel
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.ModInstaller
import org.yuzu.yuzu_emu.utils.ViewUtils.updateMargins
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Browses mod packages hosted on GitHub (Eden Duo companions, ultrawide/FPS mods)
 * and installs them into the matching game load folder.
 */
class ModFetcherFragment : Fragment() {
    private var _binding: FragmentModFetcherBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<ModFetcherFragmentArgs>()

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val addonViewModel: AddonViewModel by activityViewModels()

    private val client = OkHttpClient()
    private val mapper = jacksonObjectMapper()

    private lateinit var sourceAdapter: ModSourceAdapter

    private data class SourceDefinition(
        val nameId: Int,
        val descriptionId: Int,
        val fetch: () -> List<ModPackage>
    )

    private val sourceDefinitions: List<SourceDefinition> by lazy {
        listOf(
            SourceDefinition(
                R.string.mod_browser_source_eden_duo,
                R.string.mod_browser_source_eden_duo_description
            ) {
                fetchReleasePackages(
                    EDEN_DUO_COMPANIONS_REPO,
                    getString(R.string.mod_browser_source_eden_duo)
                )
            },
            SourceDefinition(
                R.string.mod_browser_source_ultrawide,
                R.string.mod_browser_source_ultrawide_description
            ) {
                fetchRepositoryZips(
                    ULTRAWIDE_MODS_REPO,
                    getString(R.string.mod_browser_source_ultrawide)
                )
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModFetcherBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(visible = false)

        binding.toolbarMods.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        args.game?.let { game ->
            binding.toolbarMods.title = getString(R.string.mod_browser_game, game.title)
            binding.textHint.text =
                getString(R.string.mod_browser_filter_hint) + "\n\n" +
                getString(R.string.mod_browser_dsmod_notice)
        }

        binding.listMods.layoutManager = LinearLayoutManager(context)
        sourceAdapter = ModSourceAdapter { pkg -> install(pkg) }
        binding.listMods.adapter = sourceAdapter

        setInsets()
        fetchSources()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun fetchSources() {
        binding.loadingIndicator.isVisible = true
        binding.textEmpty.isVisible = false
        val filterGame = args.game

        viewLifecycleOwner.lifecycleScope.launch {
            val sources = mutableListOf<ModSource>()
            val errors = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                sourceDefinitions.forEach { definition ->
                    try {
                        var packages = definition.fetch()
                        if (filterGame != null) {
                            packages = packages.filter {
                                it.titleId != null &&
                                    it.titleId.equals(filterGame.programIdHex, ignoreCase = true)
                            }
                        }
                        if (packages.isNotEmpty() || filterGame == null) {
                            sources.add(
                                ModSource(
                                    getString(definition.nameId),
                                    getString(definition.descriptionId),
                                    packages
                                )
                            )
                        }
                    } catch (e: Exception) {
                        errors.add(getString(definition.nameId) + ": " + e.message)
                    }
                }
            }

            if (_binding == null) {
                return@launch
            }
            binding.loadingIndicator.isVisible = false
            sourceAdapter.updateSources(sources)
            binding.textEmpty.isVisible = sources.all { it.packages.isEmpty() }

            if (errors.isNotEmpty()) {
                MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(getString(R.string.error_during_fetch))
                    .setMessage(errors.joinToString("\n"))
                    .setPositiveButton(getString(R.string.ok)) { dialog, _ -> dialog.cancel() }
                    .show()
            }
        }
    }

    /** GitHub releases whose assets are mod ZIPs (Eden Duo companions). */
    @Throws(IOException::class)
    private fun fetchReleasePackages(repo: String, sourceName: String): List<ModPackage> {
        val root = getJson("https://api.github.com/repos/$repo/releases?per_page=100")
        val packages = mutableListOf<ModPackage>()
        if (!root.isArray) {
            return packages
        }
        for (node in root) {
            val releaseName = node.get("name")?.asText()?.takeIf { it.isNotBlank() }
                ?: node.get("tag_name")?.asText() ?: continue
            val body = node.get("body")?.asText() ?: ""
            val assets = node.get("assets") ?: continue
            for (asset in assets) {
                val fileName = asset.get("name")?.asText() ?: continue
                if (!fileName.endsWith(".zip", ignoreCase = true)) continue
                val url = asset.get("browser_download_url")?.asText() ?: continue
                packages.add(
                    ModPackage(
                        title = releaseName,
                        description = body.lineSequence().firstOrNull { it.isNotBlank() }
                            ?.trim()?.take(160) ?: "",
                        fileName = fileName,
                        url = url,
                        titleId = ModInstaller.titleIdFromName(fileName),
                        sourceName = sourceName
                    )
                )
            }
        }
        return packages
    }

    /** ZIP files stored directly in a repository (Fl4sh9174 ultrawide/FPS mods). */
    @Throws(IOException::class)
    private fun fetchRepositoryZips(repo: String, sourceName: String): List<ModPackage> {
        val root = getJson("https://api.github.com/repos/$repo/contents/")
        val packages = mutableListOf<ModPackage>()
        if (!root.isArray) {
            return packages
        }
        for (node in root) {
            if (node.get("type")?.asText() != "file") continue
            val fileName = node.get("name")?.asText() ?: continue
            if (!fileName.endsWith(".zip", ignoreCase = true)) continue
            val url = node.get("download_url")?.asText() ?: continue
            val title = fileName.removeSuffix(".zip")
                .replace(Regex("\\s*\\[[0-9A-Fa-f]{16}]"), "")
                .replace("[mods]", "")
                .trim()
            packages.add(
                ModPackage(
                    title = title,
                    description = "",
                    fileName = fileName,
                    url = url,
                    titleId = ModInstaller.titleIdFromName(fileName),
                    sourceName = sourceName
                )
            )
        }
        return packages.sortedBy { it.title.lowercase() }
    }

    @Throws(IOException::class)
    private fun getJson(url: String): JsonNode {
        val request = Request.Builder().url(url).build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code)
            }
            response.body?.string() ?: throw IOException("Empty response")
        }
        return mapper.readTree(body)
    }

    private fun install(pkg: ModPackage) {
        val titleId = pkg.titleId ?: args.game?.programIdHex
        if (titleId != null) {
            downloadAndInstall(pkg, titleId)
            return
        }
        chooseGame { game -> downloadAndInstall(pkg, game.programIdHex) }
    }

    private fun chooseGame(onSelected: (Game) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val games = withContext(Dispatchers.IO) {
                gamesViewModel.games.value.ifEmpty { GameHelper.getGames() }
            }.filter { it.programId.isNotEmpty() }
            if (games.isEmpty()) {
                Toast.makeText(requireContext(), R.string.mod_install_no_games, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.mod_install_select_game)
                .setItems(games.map { it.title }.toTypedArray()) { _, which ->
                    onSelected(games[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun downloadAndInstall(pkg: ModPackage, titleIdHex: String) {
        val context = requireContext().applicationContext
        val activity = requireActivity()
        ProgressDialogFragment.newInstance(
            activity,
            R.string.mod_installing,
            false
        ) { progressCallback, messageCallback ->
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            cacheDir.mkdirs()
            val file = File(cacheDir, pkg.fileName)
            try {
                messageCallback(context.getString(R.string.downloading))
                val request = Request.Builder()
                    .url(pkg.url)
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
                        val stream = response.body?.byteStream()
                            ?: throw IOException(context.getString(R.string.empty_response_body))
                        stream.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                    }

                messageCallback(context.getString(R.string.installing))
                val installed = ModInstaller.installZip(file, titleIdHex, progressCallback)
                if (installed == 0) {
                    return@newInstance MessageDialogFragment.newInstance(
                        activity,
                        titleId = R.string.invalid_directory,
                        descriptionId = R.string.mod_install_invalid_zip
                    )
                }
                addonViewModel.refreshAddons(force = true)
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        sourceAdapter.notifyDataSetChanged()
                    }
                }
                return@newInstance context.getString(R.string.mod_installed, installed)
            } catch (e: Exception) {
                return@newInstance MessageDialogFragment.newInstance(
                    activity,
                    titleId = R.string.error_during_fetch,
                    descriptionString = context.getString(
                        R.string.mod_download_failed,
                        e.message ?: ""
                    )
                )
            } finally {
                file.delete()
            }
        }.show(parentFragmentManager, ProgressDialogFragment.TAG)
    }

    private fun setInsets() = ViewCompat.setOnApplyWindowInsetsListener(
        binding.root
    ) { _: View, windowInsets: WindowInsetsCompat ->
        val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

        val leftInsets = barInsets.left + cutoutInsets.left
        val rightInsets = barInsets.right + cutoutInsets.right

        binding.toolbarMods.updateMargins(left = leftInsets, right = rightInsets)
        binding.listMods.updateMargins(left = leftInsets, right = rightInsets)
        binding.listMods.updatePadding(
            bottom = barInsets.bottom + resources.getDimensionPixelSize(
                R.dimen.spacing_bottom_list_fab
            )
        )

        windowInsets
    }

    companion object {
        const val EDEN_DUO_COMPANIONS_REPO = "igawa6/eden-duo-companions"
        const val ULTRAWIDE_MODS_REPO = "Fl4sh9174/Switch-Emulator-Ultrawide-FPS-Mods"
    }
}
