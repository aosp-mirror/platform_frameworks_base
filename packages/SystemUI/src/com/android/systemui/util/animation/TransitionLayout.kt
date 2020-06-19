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

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.statusbar.CrossFadeHelper

/**
 * A view that handles displaying of children and transitions of them in an optimized way,
 * minimizing the number of measure passes, while allowing for maximum flexibility
 * and interruptibility.
 */
class TransitionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val boundsRect = Rect()
    private val originalGoneChildrenSet: MutableSet<Int> = mutableSetOf()
    private val originalViewAlphas: MutableMap<Int, Float> = mutableMapOf()
    private var measureAsConstraint: Boolean = false
    private var currentState: TransitionViewState = TransitionViewState()
    private var updateScheduled = false

    /**
     * The measured state of this view which is the one we will lay ourselves out with. This
     * may differ from the currentState if there is an external animation or transition running.
     * This state will not be used to measure the widgets, where the current state is preferred.
     */
    var measureState: TransitionViewState = TransitionViewState()
    private val preDrawApplicator = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            updateScheduled = false
            viewTreeObserver.removeOnPreDrawListener(this)
            applyCurrentState()
            return true
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.id == View.NO_ID) {
                child.id = i
            }
            if (child.visibility == GONE) {
                originalGoneChildrenSet.add(child.id)
            }
            originalViewAlphas[child.id] = child.alpha
        }
    }

    /**
     * Apply the current state to the view and its widgets
     */
    private fun applyCurrentState() {
        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val widgetState = currentState.widgetStates.get(child.id) ?: continue
            if (child.measuredWidth != widgetState.measureWidth ||
                    child.measuredHeight != widgetState.measureHeight) {
                val measureWidthSpec = MeasureSpec.makeMeasureSpec(widgetState.measureWidth,
                        MeasureSpec.EXACTLY)
                val measureHeightSpec = MeasureSpec.makeMeasureSpec(widgetState.measureHeight,
                        MeasureSpec.EXACTLY)
                child.measure(measureWidthSpec, measureHeightSpec)
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
            val left = widgetState.x.toInt()
            val top = widgetState.y.toInt()
            child.setLeftTopRightBottom(left, top, left + widgetState.width,
                    top + widgetState.height)
            child.scaleX = widgetState.scale
            child.scaleY = widgetState.scale
            val clipBounds = child.clipBounds ?: Rect()
            clipBounds.set(0, 0, widgetState.width, widgetState.height)
            child.clipBounds = clipBounds
            CrossFadeHelper.fadeIn(child, widgetState.alpha)
            child.visibility = if (widgetState.gone || widgetState.alpha == 0.0f) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
        }
        updateBounds()
    }

    private fun applyCurrentStateOnPredraw() {
        if (!updateScheduled) {
            updateScheduled = true
            viewTreeObserver.addOnPreDrawListener(preDrawApplicator)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (measureAsConstraint) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val widgetState = currentState.widgetStates.get(child.id) ?: continue
                val measureWidthSpec = MeasureSpec.makeMeasureSpec(widgetState.measureWidth,
                        MeasureSpec.EXACTLY)
                val measureHeightSpec = MeasureSpec.makeMeasureSpec(widgetState.measureHeight,
                        MeasureSpec.EXACTLY)
                child.measure(measureWidthSpec, measureHeightSpec)
            }
            setMeasuredDimension(measureState.width, measureState.height)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (measureAsConstraint) {
            super.onLayout(changed, left, top, right, bottom)
        } else {
            val childCount = childCount
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
            // Reapply the bounds to update the background
            applyCurrentState()
        }
    }

    override fun dispatchDraw(canvas: Canvas?) {
        val clip = !boundsRect.isEmpty
        if (clip) {
            canvas?.save()
            canvas?.clipRect(boundsRect)
        }
        super.dispatchDraw(canvas)
        if (clip) {
            canvas?.restore()
        }
    }

    private fun updateBounds() {
        val layoutLeft = left
        val layoutTop = top
        setLeftTopRightBottom(layoutLeft, layoutTop, layoutLeft + currentState.width,
                layoutTop + currentState.height)
        translationX = currentState.translation.x
        translationY = currentState.translation.y
        boundsRect.set(0, 0, (width + translationX).toInt(), (height + translationY).toInt())
    }

    /**
     * Calculates a view state for a given ConstraintSet and measurement, saving all positions
     * of all widgets.
     *
     * @param input the measurement input this should be done with
     * @param constraintSet the constraint set to apply
     * @param resusableState the result that we can reuse to minimize memory impact
     */
    fun calculateViewState(
        input: MeasurementInput,
        constraintSet: ConstraintSet,
        existing: TransitionViewState? = null
    ): TransitionViewState {

        val result = existing ?: TransitionViewState()
        // Reset gone children to the original state
        applySetToFullLayout(constraintSet)
        val previousHeight = measuredHeight
        val previousWidth = measuredWidth

        // Let's measure outselves as a ConstraintLayout
        measureAsConstraint = true
        measure(input.widthMeasureSpec, input.heightMeasureSpec)
        val layoutLeft = left
        val layoutTop = top
        layout(layoutLeft, layoutTop, layoutLeft + measuredWidth, layoutTop + measuredHeight)
        measureAsConstraint = false
        result.initFromLayout(this)
        ensureViewsNotGone()

        // Let's reset our layout to have the right size again
        setMeasuredDimension(previousWidth, previousHeight)
        applyCurrentStateOnPredraw()
        return result
    }

    private fun applySetToFullLayout(constraintSet: ConstraintSet) {
        // Let's reset our views to the initial gone state of the layout, since the constraintset
        // might only be a subset of the views. Otherwise the gone state would be calculated
        // wrongly later if we made this invisible in the layout (during apply we make sure they
        // are invisible instead
        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (originalGoneChildrenSet.contains(child.id)) {
                child.visibility = View.GONE
            }
            // Reset the alphas, to only have the alphas present from the constraintset
            child.alpha = originalViewAlphas[child.id] ?: 1.0f
        }
        // Let's now apply the constraintSet to get the full state
        constraintSet.applyTo(this)
    }

    /**
     * Ensures that our views are never gone but invisible instead, this allows us to animate them
     * without remeasuring.
     */
    private fun ensureViewsNotGone() {
        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val widgetState = currentState.widgetStates.get(child.id)
            child.visibility = if (widgetState?.gone != false) View.INVISIBLE else View.VISIBLE
        }
    }

    /**
     * Set the state that should be applied to this View
     *
     */
    fun setState(state: TransitionViewState) {
        currentState = state
        applyCurrentState()
    }
}

class TransitionViewState {
    var widgetStates: MutableMap<Int, WidgetState> = mutableMapOf()
    var width: Int = 0
    var height: Int = 0
    val translation = PointF()
    fun copy(reusedState: TransitionViewState? = null): TransitionViewState {
        // we need a deep copy of this, so we can't use a data class
        val copy = reusedState ?: TransitionViewState()
        copy.width = width
        copy.height = height
        copy.translation.set(translation.x, translation.y)
        for (entry in widgetStates) {
            copy.widgetStates[entry.key] = entry.value.copy()
        }
        return copy
    }

    fun initFromLayout(transitionLayout: TransitionLayout) {
        val childCount = transitionLayout.childCount
        for (i in 0 until childCount) {
            val child = transitionLayout.getChildAt(i)
            val widgetState = widgetStates.getOrPut(child.id, {
                WidgetState(0.0f, 0.0f, 0, 0, 0, 0, 0.0f)
            })
            widgetState.initFromLayout(child)
        }
        width = transitionLayout.measuredWidth
        height = transitionLayout.measuredHeight
        translation.set(0.0f, 0.0f)
    }
}

data class WidgetState(
    var x: Float = 0.0f,
    var y: Float = 0.0f,
    var width: Int = 0,
    var height: Int = 0,
    var measureWidth: Int = 0,
    var measureHeight: Int = 0,
    var alpha: Float = 1.0f,
    var scale: Float = 1.0f,
    var gone: Boolean = false
) {
    fun initFromLayout(view: View) {
        gone = view.visibility == View.GONE
        if (gone) {
            val layoutParams = view.layoutParams as ConstraintLayout.LayoutParams
            x = layoutParams.constraintWidget.left.toFloat()
            y = layoutParams.constraintWidget.top.toFloat()
            width = layoutParams.constraintWidget.width
            height = layoutParams.constraintWidget.height
            measureHeight = height
            measureWidth = width
            alpha = 0.0f
            scale = 0.0f
        } else {
            x = view.left.toFloat()
            y = view.top.toFloat()
            width = view.width
            height = view.height
            measureWidth = width
            measureHeight = height
            gone = view.visibility == View.GONE
            alpha = view.alpha
            // No scale by default. Only during transitions!
            scale = 1.0f
        }
    }
}
