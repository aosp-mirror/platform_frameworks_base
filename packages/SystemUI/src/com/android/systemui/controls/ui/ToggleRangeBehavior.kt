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
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.TemperatureControlTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.util.Log
import android.util.MathUtils
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.android.systemui.R
import com.android.app.animation.Interpolators
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MAX_LEVEL
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MIN_LEVEL
import java.util.IllegalFormatException

/**
 * Supports [ToggleRangeTemplate] and [RangeTemplate], as well as when one of those templates is
 * defined as the subtemplate in [TemperatureControlTemplate].
 */
class ToggleRangeBehavior : Behavior {
    private var rangeAnimator: ValueAnimator? = null
    lateinit var clipLayer: Drawable
    lateinit var templateId: String
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    lateinit var rangeTemplate: RangeTemplate
    lateinit var context: Context
    var currentStatusText: CharSequence = ""
    var currentRangeValue: String = ""
    var isChecked: Boolean = false
    var isToggleable: Boolean = false
    var colorOffset: Int = 0

    companion object {
        private const val DEFAULT_FORMAT = "%.1f"
    }

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
        context = cvh.context

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

    private fun setup(template: ToggleRangeTemplate) {
        rangeTemplate = template.getRange()
        isToggleable = true
        isChecked = template.isChecked()
    }

    private fun setup(template: RangeTemplate) {
        rangeTemplate = template

        // only show disabled state when value is at the minimum
        isChecked = rangeTemplate.currentValue != rangeTemplate.minValue
    }

    private fun setupTemplate(template: ControlTemplate): Boolean {
        return when (template) {
            is ToggleRangeTemplate -> {
                setup(template)
                true
            }
            is RangeTemplate -> {
                setup(template)
                true
            }
            is TemperatureControlTemplate -> setupTemplate(template.getTemplate())
            else -> {
                Log.e(ControlsUiController.TAG, "Unsupported template type: $template")
                false
            }
        }
    }

    override fun bind(cws: ControlWithState, colorOffset: Int) {
        this.control = cws.control!!
        this.colorOffset = colorOffset

        currentStatusText = control.getStatusText()

        // ControlViewHolder sets a long click listener, but we want to handle touch in
        // here instead, otherwise we'll have state conflicts.
        cvh.layout.setOnLongClickListener(null)

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        val template = control.getControlTemplate()
        if (!setupTemplate(template)) return
        templateId = template.getTemplateId()

        updateRange(rangeToLevelValue(rangeTemplate.currentValue), isChecked,
            /* isDragging */ false)

        cvh.applyRenderInfo(isChecked, colorOffset)

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

                if (isChecked) {
                    val rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(type, min, max, current)
                    info.setRangeInfo(rangeInfo)
                }
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            }

            override fun performAccessibilityAction(
                host: View,
                action: Int,
                arguments: Bundle?
            ): Boolean {
                val handled = when (action) {
                    AccessibilityNodeInfo.ACTION_CLICK -> {
                        if (!isToggleable) {
                            false
                        } else {
                            cvh.controlActionCoordinator.toggle(cvh, templateId, isChecked)
                            true
                        }
                    }
                    AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                        cvh.controlActionCoordinator.longPress(cvh)
                        true
                    }
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() -> {
                        if (arguments == null || !arguments.containsKey(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                            false
                        } else {
                            val value = arguments.getFloat(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                            val level = rangeToLevelValue(value)
                            updateRange(level, isChecked, /* isDragging */ true)
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
            ): Boolean = true
        })
    }

    fun beginUpdateRange() {
        cvh.userInteractionInProgress = true
        cvh.setStatusTextSize(context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_expanded).toFloat())
    }

    fun updateRange(level: Int, checked: Boolean, isDragging: Boolean) {
        val newLevel = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level))

        // If the current level is at the minimum and the user is dragging, set the control to
        // the enabled state to indicate their intention to enable the device. This will update
        // control colors to support dragging.
        if (clipLayer.level == MIN_LEVEL && newLevel > MIN_LEVEL) {
            cvh.applyRenderInfo(checked, colorOffset, false /* animated */)
        }

        rangeAnimator?.cancel()
        if (isDragging) {
            val isEdge = newLevel == MIN_LEVEL || newLevel == MAX_LEVEL
            if (clipLayer.level != newLevel) {
                cvh.controlActionCoordinator.drag(isEdge)
                clipLayer.level = newLevel
            }
        } else if (newLevel != clipLayer.level) {
            rangeAnimator = ValueAnimator.ofInt(cvh.clipLayer.level, newLevel).apply {
                addUpdateListener {
                    cvh.clipLayer.level = it.animatedValue as Int
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
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
            if (isDragging) {
                cvh.setStatusText(currentRangeValue, /* immediately */ true)
            } else {
                cvh.setStatusText("$currentStatusText $currentRangeValue")
            }
        } else {
            cvh.setStatusText(currentStatusText)
        }
    }

    private fun format(primaryFormat: String, backupFormat: String, value: Float): String {
        return try {
            String.format(primaryFormat, findNearestStep(value))
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
        cvh.setStatusTextSize(context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_normal).toFloat())
        cvh.setStatusText("$currentStatusText $currentRangeValue", /* immediately */ true)
        cvh.controlActionCoordinator.setValue(cvh, rangeTemplate.getTemplateId(),
            findNearestStep(levelToRangeValue(clipLayer.getLevel())))
        cvh.userInteractionInProgress = false
    }

    fun findNearestStep(value: Float): Float {
        var minDiff = Float.MAX_VALUE

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
            cvh.controlActionCoordinator.longPress(cvh)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            xDiff: Float,
            yDiff: Float
        ): Boolean {
            if (!isDragging) {
                v.getParent().requestDisallowInterceptTouchEvent(true)
                beginUpdateRange()
                isDragging = true
            }

            val ratioDiff = -xDiff / v.width
            val changeAmount = ((MAX_LEVEL - MIN_LEVEL) * ratioDiff).toInt()
            updateRange(clipLayer.level + changeAmount, checked = true, isDragging = true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isToggleable) return false
            cvh.controlActionCoordinator.toggle(cvh, templateId, isChecked)
            return true
        }
    }
}
