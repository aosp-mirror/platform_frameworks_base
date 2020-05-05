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
 * A special view that is designed to host a single "unique object". The unique object is
 * dynamically added and removed from this view and may transition to other UniqueObjectHostViews
 * available in the system.
 * This is useful to share a singular instance of a view that can transition between completely
 * independent parts of the view hierarchy.
 * If the view currently hosts the unique object, it's measuring it normally,
 * but if it's not attached, it will obtain the size by requesting a measure, as if it were
 * always attached.
 */
class UniqueObjectHostView(
    context: Context
) : FrameLayout(context) {
    lateinit var measurementCache : GuaranteedMeasurementCache
    var onMeasureListener: ((MeasurementInput) -> Unit)? = null

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val paddingHorizontal = paddingStart + paddingEnd
        val paddingVertical = paddingTop + paddingBottom
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingHorizontal
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthMeasureSpec))
        val height = MeasureSpec.getSize(heightMeasureSpec) - paddingVertical
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.getMode(heightMeasureSpec))
        val measurementInput = MeasurementInputData(widthSpec, heightSpec)
        onMeasureListener?.apply {
            invoke(measurementInput)
        }
        if (!isCurrentHost()) {
            // We're not currently the host, let's get the dimension from our cache (this might
            // perform a measuring if the cache doesn't have it yet)
            // The goal here is that the view will always have a consistent measuring, regardless
            // if it's attached or not.
            // The behavior is therefore very similar to the view being persistently attached to
            // this host, which can prevent flickers. It also makes sure that we always know
            // the size of the view during transitions even if it has never been attached here
            // before.
            val (cachedWidth, cachedHeight) = measurementCache.obtainMeasurement(measurementInput)
            setMeasuredDimension(cachedWidth + paddingHorizontal, cachedHeight + paddingVertical)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // Let's update our cache
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
