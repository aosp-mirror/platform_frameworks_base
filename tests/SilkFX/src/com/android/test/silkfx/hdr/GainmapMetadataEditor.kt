/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.test.silkfx.hdr

import android.graphics.Gainmap
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import com.android.test.silkfx.R

data class GainmapMetadata(
        var ratioMin: Float,
        var ratioMax: Float,
        var capacityMin: Float,
        var capacityMax: Float,
        var gamma: Float,
        var offsetSdr: Float,
        var offsetHdr: Float
)

/**
 * Note: This can only handle single-channel gainmaps nicely. It will force all 3-channel
 * metadata to have the same value single value and is not intended to be a robust demonstration
 * of gainmap metadata editing
 */
class GainmapMetadataEditor(val parent: ViewGroup, val renderView: View) {
    private lateinit var gainmap: Gainmap

    private var metadataPopup: PopupWindow? = null

    private var originalMetadata: GainmapMetadata = GainmapMetadata(
            1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f)
    private var currentMetadata: GainmapMetadata = originalMetadata.copy()

    private val maxProgress = 100.0f

    private val minRatioMin = .001f
    private val maxRatioMin = 1.0f
    private val minRatioMax = 1.0f
    private val maxRatioMax = 16.0f
    private val minCapacityMin = 1.0f
    private val maxCapacityMin = maxRatioMax
    private val minCapacityMax = 1.001f
    private val maxCapacityMax = maxRatioMax
    private val minGamma = 0.1f
    private val maxGamma = 3.0f
    // Min and max offsets are 0.0 and 1.0 respectively

    fun setGainmap(newGainmap: Gainmap) {
        gainmap = newGainmap
        originalMetadata = GainmapMetadata(gainmap.getRatioMin()[0],
                gainmap.getRatioMax()[0], gainmap.getMinDisplayRatioForHdrTransition(),
                gainmap.getDisplayRatioForFullHdr(), gainmap.getGamma()[0],
                gainmap.getEpsilonSdr()[0], gainmap.getEpsilonHdr()[0])
        currentMetadata = originalMetadata.copy()
    }

    fun editedGainmap(): Gainmap {
        applyMetadata(currentMetadata)
        return gainmap
    }

    fun closeEditor() {
        metadataPopup?.let {
            it.dismiss()
            metadataPopup = null
        }
    }

    fun openEditor() {
        if (metadataPopup != null) return

        val view = LayoutInflater.from(parent.getContext()).inflate(R.layout.gainmap_metadata, null)

        metadataPopup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        metadataPopup!!.showAtLocation(view, Gravity.CENTER, 0, 0)

        (view.getParent() as ViewGroup).removeView(view)
        parent.addView(view)

        view.requireViewById<Button>(R.id.gainmap_metadata_done).setOnClickListener {
            closeEditor()
        }

        view.requireViewById<Button>(R.id.gainmap_metadata_reset).setOnClickListener {
            resetGainmapMetadata()
        }

        updateMetadataUi()

        val gainmapMinSeek = view.requireViewById<SeekBar>(R.id.gainmap_metadata_gainmapmin)
        val gainmapMaxSeek = view.requireViewById<SeekBar>(R.id.gainmap_metadata_gainmapmax)
        val capacityMinSeek = view.requireViewById<SeekBar>(R.id.gainmap_metadata_capacitymin)
        val capacityMaxSeek = view.requireViewById<SeekBar>(R.id.gainmap_metadata_capacitymax)
        val gammaSeek = view.requireViewById<SeekBar>(R.id.gainmap_metadata_gamma)
        val offsetSdrSeek = view.requireViewById<SeekBar>(R.id.gainmap_metadata_offsetsdr)
        val offsetHdrSeek = view.requireViewById<SeekBar>(R.id.gainmap_metadata_offsethdr)
        arrayOf(gainmapMinSeek, gainmapMaxSeek, capacityMinSeek, capacityMaxSeek, gammaSeek,
                offsetSdrSeek, offsetHdrSeek).forEach {
            it.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val normalized = progress.toFloat() / maxProgress
                    when (seekBar) {
                        gainmapMinSeek -> updateGainmapMin(normalized)
                        gainmapMaxSeek -> updateGainmapMax(normalized)
                        capacityMinSeek -> updateCapacityMin(normalized)
                        capacityMaxSeek -> updateCapacityMax(normalized)
                        gammaSeek -> updateGamma(normalized)
                        offsetSdrSeek -> updateOffsetSdr(normalized)
                        offsetHdrSeek -> updateOffsetHdr(normalized)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
    }

    private fun updateMetadataUi() {
        val gainmapMinSeek = parent.requireViewById<SeekBar>(R.id.gainmap_metadata_gainmapmin)
        val gainmapMaxSeek = parent.requireViewById<SeekBar>(R.id.gainmap_metadata_gainmapmax)
        val capacityMinSeek = parent.requireViewById<SeekBar>(R.id.gainmap_metadata_capacitymin)
        val capacityMaxSeek = parent.requireViewById<SeekBar>(R.id.gainmap_metadata_capacitymax)
        val gammaSeek = parent.requireViewById<SeekBar>(R.id.gainmap_metadata_gamma)
        val offsetSdrSeek = parent.requireViewById<SeekBar>(R.id.gainmap_metadata_offsetsdr)
        val offsetHdrSeek = parent.requireViewById<SeekBar>(R.id.gainmap_metadata_offsethdr)

        gainmapMinSeek.setProgress(
                ((currentMetadata.ratioMin - minRatioMin) / maxRatioMin * maxProgress).toInt())
        gainmapMaxSeek.setProgress(
                ((currentMetadata.ratioMax - minRatioMax) / maxRatioMax * maxProgress).toInt())
        capacityMinSeek.setProgress(
                ((currentMetadata.capacityMin - minCapacityMin) / maxCapacityMin * maxProgress).toInt())
        capacityMaxSeek.setProgress(
                ((currentMetadata.capacityMax - minCapacityMax) / maxCapacityMax * maxProgress).toInt())
        gammaSeek.setProgress(
                ((currentMetadata.gamma - minGamma) / maxGamma * maxProgress).toInt())
        // Log base 3 via: log_b(x) = log_y(x) / log_y(b)
        offsetSdrSeek.setProgress(
                ((1.0 - Math.log(currentMetadata.offsetSdr.toDouble() / Math.log(3.0)) / -11.0)
                        .toFloat() * maxProgress).toInt())
        offsetHdrSeek.setProgress(
                ((1.0 - Math.log(currentMetadata.offsetHdr.toDouble() / Math.log(3.0)) / -11.0)
                        .toFloat() * maxProgress).toInt())

        parent.requireViewById<TextView>(R.id.gainmap_metadata_gainmapmin_val).setText(
                "%.3f".format(currentMetadata.ratioMin))
        parent.requireViewById<TextView>(R.id.gainmap_metadata_gainmapmax_val).setText(
                "%.3f".format(currentMetadata.ratioMax))
        parent.requireViewById<TextView>(R.id.gainmap_metadata_capacitymin_val).setText(
                "%.3f".format(currentMetadata.capacityMin))
        parent.requireViewById<TextView>(R.id.gainmap_metadata_capacitymax_val).setText(
                "%.3f".format(currentMetadata.capacityMax))
        parent.requireViewById<TextView>(R.id.gainmap_metadata_gamma_val).setText(
                "%.3f".format(currentMetadata.gamma))
        parent.requireViewById<TextView>(R.id.gainmap_metadata_offsetsdr_val).setText(
                "%.5f".format(currentMetadata.offsetSdr))
        parent.requireViewById<TextView>(R.id.gainmap_metadata_offsethdr_val).setText(
                "%.5f".format(currentMetadata.offsetHdr))
    }

    private fun resetGainmapMetadata() {
        currentMetadata = originalMetadata.copy()
        applyMetadata(currentMetadata)
        updateMetadataUi()
    }

    private fun applyMetadata(newMetadata: GainmapMetadata) {
        gainmap.setRatioMin(newMetadata.ratioMin, newMetadata.ratioMin, newMetadata.ratioMin)
        gainmap.setRatioMax(newMetadata.ratioMax, newMetadata.ratioMax, newMetadata.ratioMax)
        gainmap.setMinDisplayRatioForHdrTransition(newMetadata.capacityMin)
        gainmap.setDisplayRatioForFullHdr(newMetadata.capacityMax)
        gainmap.setGamma(newMetadata.gamma, newMetadata.gamma, newMetadata.gamma)
        gainmap.setEpsilonSdr(newMetadata.offsetSdr, newMetadata.offsetSdr, newMetadata.offsetSdr)
        gainmap.setEpsilonHdr(newMetadata.offsetHdr, newMetadata.offsetHdr, newMetadata.offsetHdr)
        renderView.invalidate()
    }

    private fun updateGainmapMin(normalized: Float) {
        val newValue = minRatioMin + normalized * (maxRatioMin - minRatioMin)
        parent.requireViewById<TextView>(R.id.gainmap_metadata_gainmapmin_val).setText(
                "%.3f".format(newValue))
        currentMetadata.ratioMin = newValue
        gainmap.setRatioMin(newValue, newValue, newValue)
        renderView.invalidate()
    }

    private fun updateGainmapMax(normalized: Float) {
        val newValue = minRatioMax + normalized * (maxRatioMax - minRatioMax)
        parent.requireViewById<TextView>(R.id.gainmap_metadata_gainmapmax_val).setText(
                "%.3f".format(newValue))
        currentMetadata.ratioMax = newValue
        gainmap.setRatioMax(newValue, newValue, newValue)
        renderView.invalidate()
    }

    private fun updateCapacityMin(normalized: Float) {
        val newValue = minCapacityMin + normalized * (maxCapacityMin - minCapacityMin)
        parent.requireViewById<TextView>(R.id.gainmap_metadata_capacitymin_val).setText(
                "%.3f".format(newValue))
        currentMetadata.capacityMin = newValue
        gainmap.setMinDisplayRatioForHdrTransition(newValue)
        renderView.invalidate()
    }

    private fun updateCapacityMax(normalized: Float) {
        val newValue = minCapacityMax + normalized * (maxCapacityMax - minCapacityMax)
        parent.requireViewById<TextView>(R.id.gainmap_metadata_capacitymax_val).setText(
                "%.3f".format(newValue))
        currentMetadata.capacityMax = newValue
        gainmap.setDisplayRatioForFullHdr(newValue)
        renderView.invalidate()
    }

    private fun updateGamma(normalized: Float) {
        val newValue = minGamma + normalized * (maxGamma - minGamma)
        parent.requireViewById<TextView>(R.id.gainmap_metadata_gamma_val).setText(
                "%.3f".format(newValue))
        currentMetadata.gamma = newValue
        gainmap.setGamma(newValue, newValue, newValue)
        renderView.invalidate()
    }

    private fun updateOffsetSdr(normalized: Float) {
        var newValue = 0.0f
        if (normalized > 0.0f ) {
            newValue = Math.pow(3.0, (1.0 - normalized.toDouble()) * -11.0).toFloat()
        }
        parent.requireViewById<TextView>(R.id.gainmap_metadata_offsetsdr_val).setText(
                "%.5f".format(newValue))
        currentMetadata.offsetSdr = newValue
        gainmap.setEpsilonSdr(newValue, newValue, newValue)
        renderView.invalidate()
    }

    private fun updateOffsetHdr(normalized: Float) {
        var newValue = 0.0f
        if (normalized > 0.0f ) {
            newValue = Math.pow(3.0, (1.0 - normalized.toDouble()) * -11.0).toFloat()
        }
        parent.requireViewById<TextView>(R.id.gainmap_metadata_offsethdr_val).setText(
                "%.5f".format(newValue))
        currentMetadata.offsetHdr = newValue
        gainmap.setEpsilonHdr(newValue, newValue, newValue)
        renderView.invalidate()
    }
}
