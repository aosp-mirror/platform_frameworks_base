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

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.service.controls.Control
import android.service.controls.actions.FloatAction
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.util.TypedValue

import com.android.systemui.R
import com.android.systemui.controls.ui.ControlActionCoordinator.MIN_LEVEL
import com.android.systemui.controls.ui.ControlActionCoordinator.MAX_LEVEL

import java.util.IllegalFormatException

class ToggleRangeBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var template: ToggleRangeTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    lateinit var rangeTemplate: RangeTemplate
    lateinit var statusExtra: TextView
    lateinit var status: TextView
    lateinit var context: Context

    companion object {
        private const val DEFAULT_FORMAT = "%.1f"
    }

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
        status = cvh.status
        context = status.getContext()

        cvh.applyRenderInfo(false)

        val gestureListener = ToggleRangeGestureListener(cvh.layout)
        val gestureDetector = GestureDetector(context, gestureListener)
        cvh.layout.setOnTouchListener { _: View, e: MotionEvent ->
            if (gestureDetector.onTouchEvent(e)) {
                return@setOnTouchListener true
            }

            if (e.getAction() == MotionEvent.ACTION_UP && gestureListener.isDragging) {
                gestureListener.isDragging = false
                endUpdateRange()
                return@setOnTouchListener true
            }

            return@setOnTouchListener false
        }
    }

    override fun bind(cws: ControlWithState) {
        this.control = cws.control!!

        statusExtra = cvh.statusExtra
        status.setText(control.getStatusText())

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)
        clipLayer.setLevel(MIN_LEVEL)

        template = control.getControlTemplate() as ToggleRangeTemplate
        rangeTemplate = template.getRange()

        val checked = template.isChecked()
        val currentRatio = rangeTemplate.getCurrentValue() /
                (rangeTemplate.getMaxValue() - rangeTemplate.getMinValue())
        updateRange(currentRatio, checked)

        cvh.applyRenderInfo(checked)
    }

    fun beginUpdateRange() {
        status.setVisibility(View.GONE)
        statusExtra.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_expanded).toFloat())
    }

    fun updateRange(ratioDiff: Float, checked: Boolean) {
        val changeAmount = if (checked) (MAX_LEVEL * ratioDiff).toInt() else MIN_LEVEL
        val newLevel = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, clipLayer.getLevel() + changeAmount))
        clipLayer.setLevel(newLevel)

        if (checked) {
            val newValue = levelToRangeValue()
            val formattedNewValue = format(rangeTemplate.getFormatString().toString(),
                    DEFAULT_FORMAT, newValue)

            statusExtra.setText(formattedNewValue)
            statusExtra.setVisibility(View.VISIBLE)
        } else {
            statusExtra.setText("")
            statusExtra.setVisibility(View.GONE)
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

    private fun levelToRangeValue(): Float {
        val ratio = clipLayer.getLevel().toFloat() / MAX_LEVEL
        return rangeTemplate.getMinValue() +
            (ratio * (rangeTemplate.getMaxValue() - rangeTemplate.getMinValue()))
    }

    fun endUpdateRange() {
        statusExtra.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_normal).toFloat())
        status.setVisibility(View.VISIBLE)
        cvh.action(FloatAction(rangeTemplate.getTemplateId(), findNearestStep(levelToRangeValue())))
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
            ControlActionCoordinator.longPress(this@ToggleRangeBehavior.cvh)
        }

        override fun onScroll(
            e1: MotionEvent,
            e2: MotionEvent,
            xDiff: Float,
            yDiff: Float
        ): Boolean {
            if (!isDragging) {
                this@ToggleRangeBehavior.beginUpdateRange()
                isDragging = true
            }

            this@ToggleRangeBehavior.updateRange(-xDiff / v.getWidth(), true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val th = this@ToggleRangeBehavior
            ControlActionCoordinator.toggle(th.cvh, th.template.getTemplateId(),
                    th.template.isChecked())
            return true
        }
    }
}
