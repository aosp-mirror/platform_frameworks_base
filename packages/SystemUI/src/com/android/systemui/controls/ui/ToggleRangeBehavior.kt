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

package com.android.systemui.controls.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.service.controls.Control
import android.service.controls.actions.FloatAction
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.util.Log
import android.util.MathUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.android.systemui.Interpolators
import com.android.systemui.R
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MAX_LEVEL
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MIN_LEVEL
import java.util.IllegalFormatException

class ToggleRangeBehavior : Behavior {
    private var rangeAnimator: ValueAnimator? = null
    lateinit var clipLayer: Drawable
    lateinit var template: ToggleRangeTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    lateinit var rangeTemplate: RangeTemplate
    lateinit var status: TextView
    lateinit var context: Context
    var currentStatusText: CharSequence = ""
    var currentRangeValue: String = ""

    companion object {
        private const val DEFAULT_FORMAT = "%.1f"
    }

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
        status = cvh.status
        context = status.getContext()

        cvh.applyRenderInfo(false /* enabled */, 0 /* offset */, false /* animated */)

        val gestureListener = ToggleRangeGestureListener(cvh.layout)
        val gestureDetector = GestureDetector(context, gestureListener)
        cvh.layout.setOnTouchListener { v: View, e: MotionEvent ->
            if (gestureDetector.onTouchEvent(e)) {
                // Don't return true to let the state list change to "pressed"
                return@setOnTouchListener false
            }

            if (e.getAction() == MotionEvent.ACTION_UP && gestureListener.isDragging) {
                v.getParent().requestDisallowInterceptTouchEvent(false)
                gestureListener.isDragging = false
                endUpdateRange()
                return@setOnTouchListener false
            }

            return@setOnTouchListener false
        }
    }

    override fun bind(cws: ControlWithState) {
        this.control = cws.control!!

        currentStatusText = control.getStatusText()
        status.setText(currentStatusText)

        // ControlViewHolder sets a long click listener, but we want to handle touch in
        // here instead, otherwise we'll have state conflicts.
        cvh.layout.setOnLongClickListener(null)

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        template = control.getControlTemplate() as ToggleRangeTemplate
        rangeTemplate = template.getRange()

        val checked = template.isChecked()
        updateRange(rangeToLevelValue(rangeTemplate.currentValue), checked, /* isDragging */ false)

        cvh.applyRenderInfo(checked)

        /*
         * This is custom widget behavior, so add a new accessibility delegate to
         * handle clicks and range events. Present as a seek bar control.
         */
        cvh.layout.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)

                val min = levelToRangeValue(MIN_LEVEL)
                val current = levelToRangeValue(clipLayer.getLevel())
                val max = levelToRangeValue(MAX_LEVEL)

                val step = rangeTemplate.getStepValue().toDouble()
                val type = if (step == Math.floor(step)) {
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT
                } else {
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT
                }

                val rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(type, min, max, current)
                info.setRangeInfo(rangeInfo)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            }

            override fun performAccessibilityAction(
                host: View,
                action: Int,
                arguments: Bundle?
            ): Boolean {
                val handled = when (action) {
                    AccessibilityNodeInfo.ACTION_CLICK -> {
                        cvh.controlActionCoordinator.toggle(cvh, template.getTemplateId(),
                            template.isChecked())
                        true
                    }
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() -> {
                        if (arguments == null || !arguments.containsKey(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                            false
                        } else {
                            val value = arguments.getFloat(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                            val level = rangeToLevelValue(value - rangeTemplate.getCurrentValue())
                            updateRange(level, template.isChecked(), /* isDragging */ false)
                            endUpdateRange()
                            true
                        }
                    }
                    else -> false
                }

                return handled || super.performAccessibilityAction(host, action, arguments)
            }

            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                event: AccessibilityEvent
            ): Boolean = false
        })
    }

    fun beginUpdateRange() {
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_expanded).toFloat())
        cvh.controlActionCoordinator.setFocusedElement(cvh)
    }

    fun updateRange(level: Int, checked: Boolean, isDragging: Boolean) {
        val newLevel = if (checked) Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level)) else MIN_LEVEL

        if (newLevel == clipLayer.level) return

        rangeAnimator?.cancel()
        if (isDragging) {
            clipLayer.level = newLevel
            val isEdge = newLevel == MIN_LEVEL || newLevel == MAX_LEVEL
            cvh.controlActionCoordinator.drag(isEdge)
        } else {
            rangeAnimator = ValueAnimator.ofInt(cvh.clipLayer.level, newLevel).apply {
                addUpdateListener {
                    cvh.clipLayer.level = it.animatedValue as Int
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        rangeAnimator = null
                    }
                })
                duration = ControlViewHolder.STATE_ANIMATION_DURATION
                interpolator = Interpolators.CONTROL_STATE
                start()
            }
        }

        if (checked) {
            val newValue = levelToRangeValue(newLevel)
            currentRangeValue = format(rangeTemplate.getFormatString().toString(),
                    DEFAULT_FORMAT, newValue)
            val text = if (isDragging) {
                currentRangeValue
            } else {
                "$currentStatusText $currentRangeValue"
            }
            status.setText(text)
        } else {
            status.setText(currentStatusText)
        }
    }

    private fun format(primaryFormat: String, backupFormat: String, value: Float): String {
        return try {
            String.format(primaryFormat, value)
        } catch (e: IllegalFormatException) {
            Log.w(ControlsUiController.TAG, "Illegal format in range template", e)
            if (backupFormat == "") {
                ""
            } else {
                format(backupFormat, "", value)
            }
        }
    }

    private fun levelToRangeValue(i: Int): Float {
        return MathUtils.constrainedMap(rangeTemplate.minValue, rangeTemplate.maxValue,
                MIN_LEVEL.toFloat(), MAX_LEVEL.toFloat(), i.toFloat())
    }

    private fun rangeToLevelValue(i: Float): Int {
        return MathUtils.constrainedMap(MIN_LEVEL.toFloat(), MAX_LEVEL.toFloat(),
                rangeTemplate.minValue, rangeTemplate.maxValue, i).toInt()
    }

    fun endUpdateRange() {
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_normal).toFloat())
        status.setText("$currentStatusText $currentRangeValue")
        cvh.action(FloatAction(rangeTemplate.getTemplateId(),
            findNearestStep(levelToRangeValue(clipLayer.getLevel()))))
        cvh.controlActionCoordinator.setFocusedElement(null)
    }

    fun findNearestStep(value: Float): Float {
        var minDiff = 1000f

        var f = rangeTemplate.getMinValue()
        while (f <= rangeTemplate.getMaxValue()) {
            val currentDiff = Math.abs(value - f)
            if (currentDiff < minDiff) {
                minDiff = currentDiff
            } else {
                return f - rangeTemplate.getStepValue()
            }

            f += rangeTemplate.getStepValue()
        }

        return rangeTemplate.getMaxValue()
    }

    inner class ToggleRangeGestureListener(
        val v: View
    ) : SimpleOnGestureListener() {
        var isDragging: Boolean = false

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (isDragging) {
                return
            }
            cvh.controlActionCoordinator.longPress(this@ToggleRangeBehavior.cvh)
        }

        override fun onScroll(
            e1: MotionEvent,
            e2: MotionEvent,
            xDiff: Float,
            yDiff: Float
        ): Boolean {
            if (!template.isChecked) {
                return false
            }
            if (!isDragging) {
                v.getParent().requestDisallowInterceptTouchEvent(true)
                this@ToggleRangeBehavior.beginUpdateRange()
                isDragging = true
            }

            val ratioDiff = -xDiff / v.width
            val changeAmount = ((MAX_LEVEL - MIN_LEVEL) * ratioDiff).toInt()
            this@ToggleRangeBehavior.updateRange(clipLayer.level + changeAmount,
                    checked = true, isDragging = true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val th = this@ToggleRangeBehavior
            cvh.controlActionCoordinator.toggle(th.cvh, th.template.getTemplateId(),
                    th.template.isChecked())
            return true
        }
    }
}
