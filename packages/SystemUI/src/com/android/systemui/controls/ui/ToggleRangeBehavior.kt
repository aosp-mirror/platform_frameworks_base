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
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.service.controls.Control
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.util.TypedValue

import com.android.systemui.R

class ToggleRangeBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var template: ToggleRangeTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    lateinit var rangeTemplate: RangeTemplate
    lateinit var statusExtra: TextView
    lateinit var status: TextView
    lateinit var context: Context

    override fun apply(cvh: ControlViewHolder, cws: ControlWithState) {
        this.control = cws.control!!
        this.cvh = cvh

        statusExtra = cvh.statusExtra
        status = cvh.status

        status.setText(control.getStatusText())

        context = status.getContext()

        cvh.layout.setOnTouchListener(ToggleRangeTouchListener())

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        template = control.getControlTemplate() as ToggleRangeTemplate
        rangeTemplate = template.getRange()

        val checked = template.isChecked()
        val deviceType = control.getDeviceType()

        updateRange((rangeTemplate.getCurrentValue() / 100.0f), checked)

        cvh.setEnabled(checked)
        cvh.applyRenderInfo(RenderInfo.lookup(deviceType, checked))
    }

    fun toggle() {
        cvh.action(BooleanAction(template.getTemplateId(), !template.isChecked()))

        val nextLevel = if (template.isChecked()) MIN_LEVEL else MAX_LEVEL
        clipLayer.setLevel(nextLevel)
    }

    fun beginUpdateRange() {
        status.setVisibility(View.GONE)
        statusExtra.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_expanded).toFloat())
    }

    fun updateRange(f: Float, checked: Boolean) {
        clipLayer.setLevel(if (checked) (MAX_LEVEL * f).toInt() else MIN_LEVEL)

        if (checked && f < 100.0f && f > 0.0f) {
            statusExtra.setText("" + (f * 100.0).toInt() + "%")
            statusExtra.setVisibility(View.VISIBLE)
        } else {
            statusExtra.setText("")
            statusExtra.setVisibility(View.GONE)
        }
    }

    fun endUpdateRange(f: Float) {
        statusExtra.setText(" - " + (f * 100.0).toInt() + "%")

        val newValue = rangeTemplate.getMinValue() +
            (f * (rangeTemplate.getMaxValue() - rangeTemplate.getMinValue()))

        statusExtra.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources()
                .getDimensionPixelSize(R.dimen.control_status_normal).toFloat())
        status.setVisibility(View.VISIBLE)

        cvh.action(FloatAction(rangeTemplate.getTemplateId(), findNearestStep(newValue)))
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

    inner class ToggleRangeTouchListener() : View.OnTouchListener {
        private var initialTouchX: Float = 0.0f
        private var initialTouchY: Float = 0.0f
        private var isDragging: Boolean = false
        private val minDragDiff = 20

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.getActionMasked()) {
                MotionEvent.ACTION_DOWN -> setupTouch(e)
                MotionEvent.ACTION_MOVE -> detectDrag(v, e)
                MotionEvent.ACTION_UP -> endTouch(v, e)
            }

            return true
        }

        private fun setupTouch(e: MotionEvent) {
            initialTouchX = e.getX()
            initialTouchY = e.getY()
        }

        private fun detectDrag(v: View, e: MotionEvent) {
            val xDiff = Math.abs(e.getX() - initialTouchX)
            val yDiff = Math.abs(e.getY() - initialTouchY)

            if (xDiff < minDragDiff) {
                isDragging = false
            } else {
                if (!isDragging) {
                    this@ToggleRangeBehavior.beginUpdateRange()
                }
                v.getParent().requestDisallowInterceptTouchEvent(true)
                isDragging = true
                if (yDiff > xDiff) {
                    endTouch(v, e)
                } else {
                    val percent = Math.max(0.0f, Math.min(1.0f, e.getX() / v.getWidth()))
                    this@ToggleRangeBehavior.updateRange(percent, true)
                }
            }
        }

        private fun endTouch(v: View, e: MotionEvent) {
            if (!isDragging) {
                this@ToggleRangeBehavior.toggle()
            } else {
                val percent = Math.max(0.0f, Math.min(1.0f, e.getX() / v.getWidth()))
                this@ToggleRangeBehavior.endUpdateRange(percent)
            }

            initialTouchX = 0.0f
            initialTouchY = 0.0f
            isDragging = false
        }
    }
}
