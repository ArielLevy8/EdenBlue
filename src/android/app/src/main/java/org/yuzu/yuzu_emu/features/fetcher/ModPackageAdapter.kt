// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.fetcher

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.ItemDriverGroupBinding
import org.yuzu.yuzu_emu.databinding.ItemReleaseBinding
import org.yuzu.yuzu_emu.utils.ModInstaller

/** A downloadable mod package from one of the online sources. */
data class ModPackage(
    val title: String,
    val description: String,
    val fileName: String,
    val url: String,
    val titleId: String?,
    val sourceName: String
)

/** A GitHub-backed collection of mod packages. */
data class ModSource(
    val name: String,
    val description: String,
    val packages: List<ModPackage>
)

/** Renders one source as an expandable group of packages. */
class ModSourceAdapter(
    private val onInstall: (ModPackage) -> Unit
) : RecyclerView.Adapter<ModSourceAdapter.SourceViewHolder>() {
    private var sources: List<ModSource> = emptyList()

    fun updateSources(newSources: List<ModSource>) {
        sources = newSources
        notifyDataSetChanged()
    }

    inner class SourceViewHolder(
        private val binding: ItemDriverGroupBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(source: ModSource) {
            binding.textGroupName.text = "${source.name} (${source.packages.size})"

            if (binding.recyclerReleases.layoutManager == null) {
                binding.recyclerReleases.layoutManager = LinearLayoutManager(binding.root.context)
                binding.recyclerReleases.addItemDecoration(
                    SpacingItemDecoration(
                        (binding.root.resources.displayMetrics.density * 8).toInt()
                    )
                )
            }
            binding.recyclerReleases.adapter = ModPackageAdapter(source, onInstall)

            // Expand automatically when there is only one source worth showing.
            val expanded = sources.size == 1
            binding.recyclerReleases.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.imageDropdownArrow.rotation = if (expanded) 180f else 0f

            val onClick = {
                val isVisible = binding.recyclerReleases.isVisible
                binding.recyclerReleases.visibility = if (isVisible) View.GONE else View.VISIBLE
                binding.imageDropdownArrow.rotation = if (isVisible) 0f else 180f
            }
            binding.textGroupName.setOnClickListener { onClick() }
            binding.imageDropdownArrow.setOnClickListener { onClick() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val binding = ItemDriverGroupBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SourceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        holder.bind(sources[position])
    }

    override fun getItemCount(): Int = sources.size
}

/** Renders the packages of one source, each with an install button. */
class ModPackageAdapter(
    private val source: ModSource,
    private val onInstall: (ModPackage) -> Unit
) : RecyclerView.Adapter<ModPackageAdapter.PackageViewHolder>() {

    inner class PackageViewHolder(
        private val binding: ItemReleaseBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(pkg: ModPackage) {
            val context = binding.root.context
            val installed = ModInstaller.isPackageInstalled(pkg.fileName)

            binding.textReleaseName.text = pkg.title
            binding.badgeLatest.isVisible = false
            binding.textBody.text = pkg.description
            binding.textBody.isVisible = pkg.description.isNotBlank()
            binding.buttonToggleDownloads.isVisible = false
            binding.imageDownloadsArrow.isVisible = false
            binding.containerDownloads.isVisible = true
            binding.containerDownloads.removeAllViews()

            val button = MaterialButton(context).apply {
                text = if (installed) {
                    context.getString(R.string.installed_mods_label, pkg.fileName)
                } else {
                    pkg.fileName
                }
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
                )
                textAlignment = MaterialButton.TEXT_ALIGNMENT_VIEW_START
                setBackgroundColor(
                    context.getColor(
                        com.google.android.material.R.color.m3_button_background_color_selector
                    )
                )
                setIconResource(if (installed) R.drawable.ic_check else R.drawable.ic_import)
                iconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorPrimary
                    )
                )
                elevation = 6f
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { onInstall(pkg) }
            }
            binding.containerDownloads.addView(button)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val binding = ItemReleaseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PackageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        holder.bind(source.packages[position])
    }

    override fun getItemCount(): Int = source.packages.size
}
