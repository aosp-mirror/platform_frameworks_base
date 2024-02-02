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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.test.silkfx.R

enum class DecodeMode {
    Full,
    Subsampled4,
    Scaled66,
    CropedSquared,
    CropedSquaredScaled33
}

fun gainmapVisualizer(gainmap: Gainmap): Bitmap {
    val map = gainmap.gainmapContents
    val gainmapVisualizer = Bitmap.createBitmap(map.width, map.height,
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
    return gainmapVisualizer
}

class GainmapDecodeTest(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private fun decode(mode: DecodeMode) {
        val source = ImageDecoder.createSource(resources.assets,
                "gainmaps/${context.assets.list("gainmaps")!![0]}")

        val sourceInfo = findViewById<TextView>(R.id.source_info)!!

        val gainmapImage = ImageDecoder.decodeBitmap(source) { decoder, info, source ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            sourceInfo.text =
                "Original size ${info.size.width}x${info.size.height}; mime-type = ${info.mimeType}"

            when (mode) {
                DecodeMode.Full -> {}
                DecodeMode.Subsampled4 -> {
                    decoder.setTargetSampleSize(4)
                }
                DecodeMode.Scaled66 -> {
                    val size = info.size
                    decoder.setTargetSize((size.width * .66).toInt(), (size.height * .66).toInt())
                }
                DecodeMode.CropedSquared -> {
                    val dimen = minOf(info.size.width, info.size.height)
                    decoder.crop = Rect(50, 50, dimen - 100, dimen - 100)
                }
                DecodeMode.CropedSquaredScaled33 -> {
                    val size = info.size
                    val targetWidth = (size.width * .33).toInt()
                    val targetHeight = (size.height * .33).toInt()
                    decoder.setTargetSize(targetWidth, targetHeight)
                    val dimen = minOf(targetWidth, targetHeight)
                    decoder.crop = Rect(50, 50, dimen - 100, dimen - 100)
                }
            }
        }

        val gainmapContents = gainmapImage.gainmap?.let { gainmapVisualizer(it) }
        val sdrBitmap = gainmapImage.also { it.gainmap = null }

        findViewById<ImageView>(R.id.sdr_source)!!.setImageBitmap(sdrBitmap)
        findViewById<TextView>(R.id.sdr_label)!!.text =
            "SDR Size: ${sdrBitmap.width}x${sdrBitmap.height}"

        findViewById<ImageView>(R.id.gainmap)!!.setImageBitmap(gainmapContents)
        findViewById<TextView>(R.id.gainmap_label)!!.text =
            "Gainmap Size: ${gainmapContents?.width ?: 0}x${gainmapContents?.height ?: 0}"
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        decode(DecodeMode.Full)

        findViewById<Button>(R.id.decode_full)!!.setOnClickListener {
            decode(DecodeMode.Full)
        }
        findViewById<Button>(R.id.decode_subsampled4)!!.setOnClickListener {
            decode(DecodeMode.Subsampled4)
        }
        findViewById<Button>(R.id.decode_scaled66)!!.setOnClickListener {
            decode(DecodeMode.Scaled66)
        }
        findViewById<Button>(R.id.decode_crop)!!.setOnClickListener {
            decode(DecodeMode.CropedSquared)
        }
        findViewById<Button>(R.id.decode_cropScaled33)!!.setOnClickListener {
            decode(DecodeMode.CropedSquaredScaled33)
        }
    }
}