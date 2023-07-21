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

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import com.android.test.silkfx.R
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

class GainmapImage(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val gainmapImages: Array<String>
    private var selectedImage = -1
    private var outputMode = R.id.output_hdr
    private var bitmap: Bitmap? = null
    private var gainmap: Gainmap? = null
    private var gainmapVisualizer: Bitmap? = null
    private lateinit var imageView: SubsamplingScaleImageView
    private lateinit var gainmapMetadataEditor: GainmapMetadataEditor

    init {
        gainmapImages = context.assets.list("gainmaps")!!
    }

    fun setImageSource(source: ImageDecoder.Source) {
        findViewById<Spinner>(R.id.image_selection)!!.visibility = View.GONE
        doDecode(source)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        imageView = findViewById(R.id.image)!!
        gainmapMetadataEditor = GainmapMetadataEditor(this, imageView)

        findViewById<RadioGroup>(R.id.output_mode)!!.also {
            it.check(outputMode)
            it.setOnCheckedChangeListener { _, checkedId ->
                outputMode = checkedId
                // Intentionally don't do anything fancy so that mode A/B comparisons are easy
                updateDisplay()
            }
        }

        val spinner = findViewById<Spinner>(R.id.image_selection)!!
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, gainmapImages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
            ) {
                setImage(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        findViewById<Button>(R.id.gainmap_metadata)!!.setOnClickListener {
            gainmapMetadataEditor.openEditor()
        }

        setImage(0)

        imageView.apply {
            isClickable = true
            // Example of animating between SDR and HDR using gainmap params; animates HDR->SDR->HDR
            // with a brief pause on SDR. The key thing here is that the gainmap's
            // minDisplayRatioForHdrTransition is animated between its original value (for full HDR)
            // and displayRatioForFullHdr (for full SDR). The view must also be invalidated during
            // the animation for the updates to take effect.
            setOnClickListener {
                if (gainmap != null && (outputMode == R.id.output_hdr ||
                                        outputMode == R.id.output_hdr_test)) {
                    val animationLengthMs: Long = 500
                    val updateListener = object : AnimatorUpdateListener {
                        override fun onAnimationUpdate(animation: ValueAnimator) {
                            imageView.invalidate()
                        }
                    }
                    val hdrToSdr = ObjectAnimator.ofFloat(
                      gainmap, "minDisplayRatioForHdrTransition",
                      gainmap!!.minDisplayRatioForHdrTransition,
                      gainmap!!.displayRatioForFullHdr).apply {
                        duration = animationLengthMs
                        addUpdateListener(updateListener)
                    }
                    val sdrToHdr = ObjectAnimator.ofFloat(
                      gainmap, "minDisplayRatioForHdrTransition",
                      gainmap!!.displayRatioForFullHdr,
                      gainmap!!.minDisplayRatioForHdrTransition).apply {
                        duration = animationLengthMs
                        addUpdateListener(updateListener)
                    }

                    AnimatorSet().apply {
                        play(hdrToSdr)
                        play(sdrToHdr).after(animationLengthMs)
                        start()
                    }
                }
            }
        }
    }

    private fun setImage(position: Int) {
        if (selectedImage == position) return
        selectedImage = position
        val source = ImageDecoder.createSource(resources.assets,
                "gainmaps/${gainmapImages[position]}")
        doDecode(source)
    }

    private fun doDecode(source: ImageDecoder.Source) {
        gainmap = null
        bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, source ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (!bitmap!!.hasGainmap()) {
            outputMode = R.id.output_sdr
            findViewById<TextView>(R.id.error_msg)!!.also {
                it.visibility = View.VISIBLE
                it.text = "Image doesn't have a gainmap, only showing in SDR"
            }
            findViewById<RadioGroup>(R.id.output_mode)!!.also {
                it.check(R.id.output_sdr)
                it.visibility = View.GONE
            }
        } else {
            findViewById<TextView>(R.id.error_msg)!!.visibility = View.GONE
            findViewById<RadioGroup>(R.id.output_mode)!!.visibility = View.VISIBLE

            gainmap = bitmap!!.gainmap
            gainmapMetadataEditor.setGainmap(gainmap)
            val map = gainmap!!.gainmapContents
            if (map.config != Bitmap.Config.ALPHA_8) {
                gainmapVisualizer = map
            } else {
                gainmapVisualizer = Bitmap.createBitmap(map.width, map.height,
                        Bitmap.Config.ARGB_8888)
                val canvas = Canvas(gainmapVisualizer!!)
                val paint = Paint()
                paint.colorFilter = ColorMatrixColorFilter(
                        floatArrayOf(
                                0f, 0f, 0f, 1f, 0f,
                                0f, 0f, 0f, 1f, 0f,
                                0f, 0f, 0f, 1f, 0f,
                                0f, 0f, 0f, 0f, 255f
                        )
                )
                canvas.drawBitmap(map, 0f, 0f, paint)
                canvas.setBitmap(null)
            }
        }

        updateDisplay()
    }

    private fun updateDisplay() {
        if (bitmap == null) return

        imageView.setImage(ImageSource.cachedBitmap(when (outputMode) {
            R.id.output_hdr -> {
                gainmapMetadataEditor.useOriginalMetadata()
                bitmap!!.gainmap = gainmap
                bitmap!!
            }

            R.id.output_hdr_test -> {
                gainmapMetadataEditor.useEditMetadata()
                bitmap!!.gainmap = gainmap
                bitmap!!
            }

            R.id.output_sdr -> {
                bitmap!!.gainmap = null; bitmap!!
            }

            R.id.output_gainmap -> gainmapVisualizer!!
            else -> throw IllegalStateException()
        }))
    }
}
