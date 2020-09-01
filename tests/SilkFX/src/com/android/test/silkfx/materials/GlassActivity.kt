/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.test.silkfx.materials

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.android.test.silkfx.R

class GlassActivity : Activity(), SeekBar.OnSeekBarChangeListener {

    lateinit var backgroundButton1: ImageView
    lateinit var backgroundButton2: ImageView
    lateinit var backgroundButton3: ImageView
    lateinit var backgroundView: ImageView
    lateinit var materialView: GlassView
    lateinit var lightMaterialSwitch: Switch
    lateinit var noiseOpacitySeekBar: SeekBar
    lateinit var materialOpacitySeekBar: SeekBar
    lateinit var scrimOpacitySeekBar: SeekBar
    lateinit var blurRadiusSeekBar: SeekBar
    lateinit var noiseOpacityValue: TextView
    lateinit var materialOpacityValue: TextView
    lateinit var scrimOpacityValue: TextView
    lateinit var blurRadiusValue: TextView

    lateinit var background: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glass)
        backgroundButton1 = requireViewById(R.id.background1)
        backgroundButton2 = requireViewById(R.id.background2)
        backgroundButton3 = requireViewById(R.id.background3)
        backgroundView = requireViewById(R.id.background)
        lightMaterialSwitch = requireViewById(R.id.lightMaterialSwitch)
        materialView = requireViewById(R.id.materialView)
        materialOpacitySeekBar = requireViewById(R.id.materialOpacity)
        blurRadiusSeekBar = requireViewById(R.id.blurRadius)
        noiseOpacitySeekBar = requireViewById(R.id.noiseOpacity)
        scrimOpacitySeekBar = requireViewById(R.id.scrimOpacity)
        noiseOpacityValue = requireViewById(R.id.noiseOpacityValue)
        materialOpacityValue = requireViewById(R.id.materialOpacityValue)
        scrimOpacityValue = requireViewById(R.id.scrimOpacityValue)
        blurRadiusValue = requireViewById(R.id.blurRadiusValue)

        background = BitmapFactory.decodeResource(resources, R.drawable.background1)
        backgroundView.setImageBitmap(background)
        materialView.backgroundBitmap = background

        blurRadiusSeekBar.setOnSeekBarChangeListener(this)
        materialOpacitySeekBar.setOnSeekBarChangeListener(this)
        noiseOpacitySeekBar.setOnSeekBarChangeListener(this)
        scrimOpacitySeekBar.setOnSeekBarChangeListener(this)

        arrayOf(blurRadiusSeekBar, materialOpacitySeekBar, noiseOpacitySeekBar,
                scrimOpacitySeekBar).forEach {
            it.setOnSeekBarChangeListener(this)
            onProgressChanged(it, it.progress, fromUser = false)
        }

        lightMaterialSwitch.setOnCheckedChangeListener { _, isChecked ->
            materialView.color = if (isChecked) Color.WHITE else Color.BLACK
        }
    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        when (seekBar) {
            blurRadiusSeekBar -> {
                materialView.blurRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                progress.toFloat(), resources.displayMetrics)
                blurRadiusValue.text = progress.toString()
            }
            materialOpacitySeekBar -> {
                materialView.materialOpacity = progress / seekBar.max.toFloat()
                materialOpacityValue.text = progress.toString()
            }
            noiseOpacitySeekBar -> {
                materialView.noiseOpacity = progress / seekBar.max.toFloat()
                noiseOpacityValue.text = progress.toString()
            }
            scrimOpacitySeekBar -> {
                materialView.scrimOpacity = progress / seekBar.max.toFloat()
                scrimOpacityValue.text = progress.toString()
            }
            else -> throw IllegalArgumentException("Unknown seek bar")
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}

    fun onBackgroundClick(view: View) {
        val resource = when (view) {
            backgroundButton1 -> R.drawable.background1
            backgroundButton2 -> R.drawable.background2
            backgroundButton3 -> R.drawable.background3
            else -> throw IllegalArgumentException("Invalid button")
        }

        background = BitmapFactory.decodeResource(resources, resource)
        backgroundView.setImageBitmap(background)
        materialView.backgroundBitmap = background
    }
}