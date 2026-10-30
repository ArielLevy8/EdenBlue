// SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogProgressBinding
import org.yuzu.yuzu_emu.model.DriverViewModel
import org.yuzu.yuzu_emu.utils.DeviceTuning
import org.yuzu.yuzu_emu.utils.GpuDriverHelper

/** Lets the user pick their chip (the detected one is preselected), then applies its preset and driver. */
class DeviceTuningDialogFragment : DialogFragment() {
    private val driverViewModel: DriverViewModel by activityViewModels()
    private val client = OkHttpClient()

    private var selected = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val gpuModel = GpuDriverHelper.hookLibPath?.let {
            GpuDriverHelper.getGpuModel(hookLibPath = it)
        }
        val detected = DeviceTuning.detectChip(gpuModel)
        selected = savedInstanceState?.getInt(SELECTED) ?: detected?.let {
            DeviceTuning.chips.indexOf(it)
        } ?: 0

        val names = DeviceTuning.chips.map {
            if (it == detected) getString(R.string.device_tuning_detected, it.label) else it.label
        }.toTypedArray()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.device_tuning)
            .setSingleChoiceItems(names, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.device_tuning_apply_with_driver) { _, _ ->
                val chip = applyPreset()
                installDriver(requireActivity(), chip)
            }
            .setNeutralButton(R.string.device_tuning_apply) { _, _ -> applyPreset() }
            .setNegativeButton(R.string.close, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SELECTED, selected)
    }

    private fun applyPreset(): DeviceTuning.Chip {
        val chip = DeviceTuning.chips[selected]
        DeviceTuning.apply(chip)
        parentFragmentManager.setFragmentResult(REQUEST_APPLIED, Bundle())
        Toast.makeText(
            requireContext(),
            getString(R.string.device_tuning_applied, chip.name),
            Toast.LENGTH_SHORT
        ).show()
        return chip
    }

    private fun installDriver(activity: FragmentActivity, chip: DeviceTuning.Chip) {
        val source = DeviceTuning.driverFor(chip.adreno)
        if (source == null || !GpuDriverHelper.supportsCustomDriverLoading()) {
            Toast.makeText(activity, R.string.device_tuning_driver_unsupported, Toast.LENGTH_LONG)
                .show()
            return
        }

        val dialogBinding = DialogProgressBinding.inflate(LayoutInflater.from(activity))
        dialogBinding.progressBar.isIndeterminate = true
        dialogBinding.title.text = activity.getString(R.string.installing_driver)
        dialogBinding.status.text = activity.getString(R.string.downloading)
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val installedName = withContext(Dispatchers.IO) {
                    val release = DeviceTuning.findDriver(client, source)
                    withContext(Dispatchers.Main) {
                        dialogBinding.status.text = release.name
                    }
                    DeviceTuning.downloadAndInstallDriver(
                        activity,
                        client,
                        release,
                        driverViewModel
                    )
                }
                progressDialog.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.device_tuning_driver_installed, installedName),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.device_tuning_driver_failed)
                    .setMessage(e.message)
                    .setPositiveButton(R.string.ok) { dialog, _ -> dialog.cancel() }
                    .show()
            }
        }
    }

    companion object {
        const val TAG = "DeviceTuningDialogFragment"
        const val REQUEST_APPLIED = "DeviceTuningApplied"
        private const val SELECTED = "Selected"

        fun newInstance(): DeviceTuningDialogFragment = DeviceTuningDialogFragment()
    }
}
