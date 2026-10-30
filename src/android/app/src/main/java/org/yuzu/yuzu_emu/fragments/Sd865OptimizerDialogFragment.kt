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
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.Sd865Optimizer

/** Explains and applies the Snapdragon 865 / Adreno 650 profile, optionally installing the Turnip driver. */
class Sd865OptimizerDialogFragment : DialogFragment() {
    private val driverViewModel: DriverViewModel by activityViewModels()
    private val client = OkHttpClient()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val message = StringBuilder(getString(R.string.sd865_optimizer_dialog_message))
        if (!GpuDriverHelper.isAdrenoGpu()) {
            message.append("\n\n").append(getString(R.string.sd865_optimizer_not_adreno_warning))
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sd865_optimizer)
            .setMessage(message)
            .setPositiveButton(R.string.sd865_optimizer_apply) { _, _ ->
                Sd865Optimizer.apply()
                Toast.makeText(
                    requireContext(),
                    R.string.sd865_optimizer_applied,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(R.string.sd865_optimizer_driver) { _, _ ->
                installRecommendedDriver(requireActivity())
            }
            .setNegativeButton(R.string.close, null)
            .create()
    }

    private fun installRecommendedDriver(activity: FragmentActivity) {
        if (!GpuDriverHelper.supportsCustomDriverLoading()) {
            Toast.makeText(
                activity,
                R.string.sd865_optimizer_driver_unsupported,
                Toast.LENGTH_LONG
            ).show()
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
                    val release = Sd865Optimizer.findRecommendedDriver(client)
                    withContext(Dispatchers.Main) {
                        dialogBinding.status.text = release.name
                    }
                    Sd865Optimizer.downloadAndInstallDriver(
                        activity,
                        client,
                        release,
                        driverViewModel
                    )
                }
                progressDialog.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.sd865_optimizer_driver_installed, installedName),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.sd865_optimizer_driver_failed)
                    .setMessage(e.message)
                    .setPositiveButton(R.string.ok) { dialog, _ -> dialog.cancel() }
                    .show()
            }
        }
    }

    companion object {
        const val TAG = "Sd865OptimizerDialogFragment"

        fun newInstance(): Sd865OptimizerDialogFragment = Sd865OptimizerDialogFragment()
    }
}
