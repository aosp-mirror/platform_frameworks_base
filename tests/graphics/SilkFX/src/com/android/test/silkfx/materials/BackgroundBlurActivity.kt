/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.android.test.silkfx.R
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import android.widget.LinearLayout
import android.widget.Button

import android.view.ViewRootImpl

class BackgroundBlurActivity : Activity(), SeekBar.OnSeekBarChangeListener  {
    var mBackgroundDrawable = PaintDrawable(Color.WHITE)
    var mBackgroundBlurRadius = 50
    var mAlphaWithBlur = 0.2f
    var mAlphaNoBlur = 0.5f

    var mBlurBehindRadius = 10
    var mDimAmountWithBlur = 0.2f
    var mDimAmountNoBlur = 0.2f

    var mBlurForceDisabled = false
    var mBatterySavingModeOn = false

    lateinit var blurBackgroundSeekBar: SeekBar
    lateinit var backgroundAlphaSeekBar : SeekBar
    lateinit var blurBehindSeekBar : SeekBar
    lateinit var dimAmountSeekBar : SeekBar

    val blurEnabledListener = { enabled : Boolean ->
        blurBackgroundSeekBar.setProgress(mBackgroundBlurRadius)
        blurBehindSeekBar.setProgress(mBlurBehindRadius)

        if (enabled) {
            setBackgroundBlur(mBackgroundBlurRadius)
            setBackgroundColorAlpha(mAlphaWithBlur)

            setBlurBehind(mBlurBehindRadius)
            setDimAmount(mDimAmountWithBlur)

            backgroundAlphaSeekBar.setProgress((mAlphaWithBlur * 100).toInt())
            dimAmountSeekBar.setProgress((mDimAmountWithBlur * 100).toInt())
        } else {
            setBackgroundColorAlpha(mAlphaNoBlur)
            setDimAmount(mDimAmountNoBlur)

            backgroundAlphaSeekBar.setProgress((mAlphaNoBlur * 100).toInt())
            dimAmountSeekBar.setProgress((mDimAmountNoBlur * 100).toInt())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_background_blur)

        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        mBackgroundDrawable.setCornerRadius(30f)
        window.setBackgroundDrawable(mBackgroundDrawable)

        mBatterySavingModeOn =
            Settings.Global.getInt(getContentResolver(), Settings.Global.LOW_POWER_MODE, 0) == 1
        setBatterySavingModeOn(mBatterySavingModeOn)

        blurBackgroundSeekBar = requireViewById(R.id.set_background_blur)
        backgroundAlphaSeekBar = requireViewById(R.id.set_background_alpha)
        blurBehindSeekBar = requireViewById(R.id.set_blur_behind)
        dimAmountSeekBar = requireViewById(R.id.set_dim_amount)

        arrayOf(blurBackgroundSeekBar, backgroundAlphaSeekBar, blurBehindSeekBar, dimAmountSeekBar)
                .forEach {
                    it.setOnSeekBarChangeListener(this)
                }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        getWindowManager().addCrossWindowBlurEnabledListener(blurEnabledListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        getWindowManager().removeCrossWindowBlurEnabledListener(blurEnabledListener)
    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        when (seekBar) {
            blurBackgroundSeekBar -> setBackgroundBlur(progress)
            backgroundAlphaSeekBar -> setBackgroundColorAlpha(progress / 100.0f)
            blurBehindSeekBar -> setBlurBehind(progress)
            dimAmountSeekBar -> setDimAmount(progress / 100.0f)
            else -> throw IllegalArgumentException("Unknown seek bar")
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}

    fun setBlurDisabled(disabled: Boolean) {
        mBlurForceDisabled = disabled
        Settings.Global.putInt(getContentResolver(), Settings.Global.DISABLE_WINDOW_BLURS,
                if (mBlurForceDisabled) 1 else 0)
        (requireViewById(R.id.toggle_blur_enabled) as Button)
                .setText(if (mBlurForceDisabled) "Enable blurs" else "Disable blurs")
    }

    fun toggleForceBlurDisabled(v: View) {
        setBlurDisabled(!mBlurForceDisabled)
    }

    fun setBackgroundBlur(radius: Int) {
        mBackgroundBlurRadius = radius
        (requireViewById(R.id.background_blur_radius) as TextView).setText(radius.toString())
        window.setBackgroundBlurRadius(mBackgroundBlurRadius)
    }

    fun setBlurBehind(radius: Int) {
        mBlurBehindRadius = radius
        (requireViewById(R.id.blur_behind_radius) as TextView).setText(radius.toString())
        window.getAttributes().setBlurBehindRadius(mBlurBehindRadius)
        window.setAttributes(window.getAttributes())
    }

    fun setDimAmount(amount: Float) {
        if (getWindowManager().isCrossWindowBlurEnabled()) {
            mDimAmountWithBlur = amount
        } else {
            mDimAmountNoBlur = amount
        }
        (requireViewById(R.id.dim_amount) as TextView).setText("%.2f".format(amount))
        window.getAttributes().dimAmount = amount
        window.setAttributes(window.getAttributes())
    }

    fun setBatterySavingModeOn(on: Boolean) {
        mBatterySavingModeOn = on
        Settings.Global.putInt(getContentResolver(),
            Settings.Global.LOW_POWER_MODE, if (on) 1 else 0)
        (requireViewById(R.id.toggle_battery_saving_mode) as Button).setText(
            if (on) "Exit low power mode" else "Enter low power mode")
    }

    fun toggleBatterySavingMode(v: View) {
        setBatterySavingModeOn(!mBatterySavingModeOn)
    }

    fun setBackgroundColorAlpha(alpha: Float) {
        if (getWindowManager().isCrossWindowBlurEnabled()) {
            mAlphaWithBlur = alpha
        } else {
            mAlphaNoBlur = alpha
        }
        (requireViewById(R.id.background_alpha) as TextView).setText("%.2f".format(alpha))
        mBackgroundDrawable.setAlpha((alpha * 255f).toInt())
        getWindowManager().updateViewLayout(window.getDecorView(), window.getAttributes())
    }
}
