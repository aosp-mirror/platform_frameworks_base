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
 * limitations under the License
 */

package com.android.systemui.util.animation

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * A special view that hosts a unique object, which only exists once, but can transition between
 * different hosts. If the view currently hosts the unique object, it's measuring it normally,
 * but if it's not attached, it will obtain the size by requesting a measure.
 */
class UniqueObjectHostView(
    context: Context
) : FrameLayout(context) {
    lateinit var measurementCache : GuaranteedMeasurementCache

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isCurrentHost()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        val paddingHorizontal = paddingStart + paddingEnd
        val paddingVertical = paddingTop + paddingBottom
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingHorizontal
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthMeasureSpec))
        val height = MeasureSpec.getSize(heightMeasureSpec) - paddingVertical
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.getMode(heightMeasureSpec))
        val measurementInput = MeasurementInputData(widthSpec, heightSpec)
        if (!isCurrentHost() || !measurementCache.contains(measurementInput)) {
            // We're not currently the host, let's get the dimension from our cache (this might
            // perform a measuring if the cache doesn't have it yet
            val (cachedWidth, cachedHeight) = measurementCache.obtainMeasurement(measurementInput)
            setMeasuredDimension(cachedWidth + paddingHorizontal, cachedHeight + paddingVertical)
        } else {
            // Let's update what we have in the cache if it's present
            val child = getChildAt(0)!!
            val output = MeasurementOutput(child.measuredWidth, child.measuredHeight)
            measurementCache.putMeasurement(measurementInput, output)
        }
    }

    private fun isCurrentHost() = childCount != 0
}

/**
 * A basic view measurement input
 */
interface MeasurementInput {
    fun sameAs(input: MeasurementInput?): Boolean {
        return equals(input)
    }
    val width : Int
        get() {
            return View.MeasureSpec.getSize(widthMeasureSpec)
        }
    val height : Int
        get() {
            return View.MeasureSpec.getSize(heightMeasureSpec)
        }
    var widthMeasureSpec: Int
    var heightMeasureSpec: Int
}

/**
 * The output of a view measurement
 */
data class MeasurementOutput(
    val measuredWidth: Int,
    val measuredHeight: Int
)

/**
 * The data object holding a basic view measurement input
 */
data class MeasurementInputData(
    override var widthMeasureSpec: Int,
    override var heightMeasureSpec: Int
) : MeasurementInput
