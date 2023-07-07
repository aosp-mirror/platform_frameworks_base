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
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.test.silkfx.R

class GainmapTransformsTest(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val sourceImage = loadSample()

    private fun loadSample(): Bitmap {
        val source = ImageDecoder.createSource(resources.assets,
                "gainmaps/${context.assets.list("gainmaps")!![0]}")

        return ImageDecoder.decodeBitmap(source) { decoder, info, source ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun process(transform: (Bitmap) -> Bitmap) {
        val result = transform(sourceImage)

        val gainmapContents = result.gainmap?.let { gainmapVisualizer(it) }
        val sdrBitmap = result.also { it.gainmap = null }

        findViewById<ImageView>(R.id.sdr_source)!!.setImageBitmap(sdrBitmap)
        findViewById<TextView>(R.id.sdr_label)!!.text =
                "SDR Size: ${sdrBitmap.width}x${sdrBitmap.height}"

        findViewById<ImageView>(R.id.gainmap)!!.setImageBitmap(gainmapContents)
        findViewById<TextView>(R.id.gainmap_label)!!.text =
                "Gainmap Size: ${gainmapContents?.width ?: 0}x${gainmapContents?.height ?: 0}"
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val sourceInfo = findViewById<TextView>(R.id.source_info)!!
        sourceInfo.text = "Original size ${sourceImage.width}x${sourceImage.height}"
        process { it.copy(Bitmap.Config.ARGB_8888, false) }

        findViewById<Button>(R.id.original)!!.setOnClickListener {
            process { it.copy(Bitmap.Config.ARGB_8888, false) }
        }

        findViewById<Button>(R.id.scaled)!!.setOnClickListener {
            process { Bitmap.createScaledBitmap(it, it.width / 3, it.height / 3, true) }
        }

        findViewById<Button>(R.id.rotate_90)!!.setOnClickListener {
            process {
                val width: Int = it.width
                val height: Int = it.height

                val m = Matrix()
                m.setRotate(90.0f, (width / 2).toFloat(), (height / 2).toFloat())
                Bitmap.createBitmap(it, 0, 0, width, height, m, false)
            }
        }

        findViewById<Button>(R.id.rotate_90_scaled)!!.setOnClickListener {
            process {
                val width: Int = it.width
                val height: Int = it.height

                val m = Matrix()
                m.setRotate(90.0f, (width / 2).toFloat(), (height / 2).toFloat())
                m.preScale(.3f, .3f)
                Bitmap.createBitmap(it, 0, 0, width, height, m, false)
            }
        }

        findViewById<Button>(R.id.crop)!!.setOnClickListener {
            process {
                val width: Int = it.width
                val height: Int = it.height
                Bitmap.createBitmap(it, width / 2, height / 2,
                        width / 4, height / 4, null, false)
            }
        }

        findViewById<Button>(R.id.crop_200)!!.setOnClickListener {
            process {
                val width: Int = it.width
                val height: Int = it.height

                val m = Matrix()
                m.setRotate(200.0f, (width / 2).toFloat(), (height / 2).toFloat())
                Bitmap.createBitmap(it, width / 2, height / 2,
                        width / 4, height / 4, m, false)
            }
        }
    }
}