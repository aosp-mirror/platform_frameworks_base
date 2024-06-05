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
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.systemui.res.R

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
    lateinit var measurementManager: MeasurementManager

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val paddingHorizontal = paddingStart + paddingEnd
        val paddingVertical = paddingTop + paddingBottom
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingHorizontal
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthMeasureSpec))
        val height = MeasureSpec.getSize(heightMeasureSpec) - paddingVertical
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.getMode(heightMeasureSpec))
        val measurementInput = MeasurementInput(widthSpec, heightSpec)

        // Let's make sure the measurementManager knows about our size, to ensure that we have
        // a value available. This might perform a measure internally if we don't have a cached
        // size.
        val (cachedWidth, cachedHeight) = measurementManager.onMeasure(measurementInput)

        if (isCurrentHost()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            getChildAt(0)?.requiresRemeasuring = false
        }
        // The goal here is that the view will always have a consistent measuring, regardless
        // if it's attached or not.
        // The behavior is therefore very similar to the view being persistently attached to
        // this host, which can prevent flickers. It also makes sure that we always know
        // the size of the view during transitions even if it has never been attached here
        // before.
        // We previously still measured the size when the view was attached, but this doesn't
        // work properly because we can set the measuredState while still attached to the
        // old host, which will trigger an inconsistency in height
        setMeasuredDimension(cachedWidth + paddingHorizontal, cachedHeight + paddingVertical)
    }

    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        if (child == null) {
            throw IllegalArgumentException("child must be non-null")
        }
        if (child.measuredWidth == 0 || measuredWidth == 0 || child.requiresRemeasuring == true) {
            super.addView(child, index, params)
            return
        }
        // Suppress layouts when adding a view. The view should already be laid out with the
        // right size when being attached to this view
        invalidate()
        addViewInLayout(child, index, params, true /* preventRequestLayout */)
        // RTL properties are normally resolved in onMeasure(), which we are intentionally skipping
        child.resolveRtlPropertiesIfNeeded()
        val left = paddingLeft
        val top = paddingTop
        val paddingHorizontal = paddingStart + paddingEnd
        val paddingVertical = paddingTop + paddingBottom
        child.layout(left,
                top,
                left + measuredWidth - paddingHorizontal,
                top + measuredHeight - paddingVertical)
    }

    private fun isCurrentHost() = childCount != 0

    interface MeasurementManager {
        fun onMeasure(input: MeasurementInput): MeasurementOutput
    }
}

/**
 * Does this view require remeasuring currently outside of the regular measure flow?
 */
var View.requiresRemeasuring: Boolean
    get() {
        val required = getTag(R.id.requires_remeasuring)
        return required?.equals(true) ?: false
    }
    set(value) {
        setTag(R.id.requires_remeasuring, value)
    }
