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
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.R

private const val MIN_LEVEL = 0
private const val MAX_LEVEL = 10000

class ControlViewHolder(
    val layout: ViewGroup,
    val controlsController: ControlsController
) {
    val icon: ImageView = layout.requireViewById(R.id.icon)
    val status: TextView = layout.requireViewById(R.id.status)
    val statusExtra: TextView = layout.requireViewById(R.id.status_extra)
    val title: TextView = layout.requireViewById(R.id.title)
    val subtitle: TextView = layout.requireViewById(R.id.subtitle)
    val context: Context = layout.getContext()
    val clipLayer: ClipDrawable
    val gd: GradientDrawable
    lateinit var cws: ControlWithState

    init {
        val ld = layout.getBackground() as LayerDrawable
        ld.mutate()
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer) as ClipDrawable
        gd = clipLayer.getDrawable() as GradientDrawable
    }

    fun bindData(cws: ControlWithState) {
        this.cws = cws

        val (status, template) = cws.control?.let {
            title.setText(it.getTitle())
            subtitle.setText(it.getSubtitle())
            Pair(it.getStatus(), it.getControlTemplate())
        } ?: run {
            title.setText(cws.ci.controlTitle)
            subtitle.setText("")
            Pair(Control.STATUS_UNKNOWN, ControlTemplate.NO_TEMPLATE)
        }

        findBehavior(status, template).apply(this, cws)
    }

    fun action(action: ControlAction) {
        controlsController.action(cws.ci, action)
    }

    private fun findBehavior(status: Int, template: ControlTemplate): Behavior {
        return when {
            status == Control.STATUS_UNKNOWN -> UnknownBehavior()
            template is ToggleTemplate -> ToggleTemplateBehavior()
            template is ToggleRangeTemplate -> ToggleRangeTemplateBehavior()
            else -> {
                object : Behavior {
                    override fun apply(cvh: ControlViewHolder, cws: ControlWithState) {
                        cvh.status.setText(cws.control?.getStatusText())
                        cvh.applyRenderInfo(findRenderInfo(cws.ci.deviceType, false))
                    }
                }
            }
        }
    }

    internal fun applyRenderInfo(ri: RenderInfo) {
        val fg = context.getResources().getColorStateList(ri.foreground, context.getTheme())
        val bg = context.getResources().getColorStateList(ri.background, context.getTheme())
        status.setTextColor(fg)
        statusExtra.setTextColor(fg)

        icon.setImageIcon(Icon.createWithResource(context, ri.iconResourceId))
        icon.setImageTintList(fg)

        gd.setColor(bg)
    }

    fun setEnabled(enabled: Boolean) {
        status.setEnabled(enabled)
        icon.setEnabled(enabled)
    }
}

private interface Behavior {
    fun apply(cvh: ControlViewHolder, cws: ControlWithState)

    fun findRenderInfo(deviceType: Int, isActive: Boolean): RenderInfo =
        deviceRenderMap.getOrDefault(deviceType, unknownDeviceMap).getValue(isActive)
}

private class UnknownBehavior : Behavior {
    override fun apply(cvh: ControlViewHolder, cws: ControlWithState) {
        cvh.status.setText("Loading...")
        cvh.applyRenderInfo(findRenderInfo(cws.ci.deviceType, false))
    }
}

private class ToggleRangeTemplateBehavior : Behavior {
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
        cvh.applyRenderInfo(findRenderInfo(deviceType, checked))
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
                    this@ToggleRangeTemplateBehavior.beginUpdateRange()
                }
                v.getParent().requestDisallowInterceptTouchEvent(true)
                isDragging = true
                if (yDiff > xDiff) {
                    endTouch(v, e)
                } else {
                    val percent = Math.max(0.0f, Math.min(1.0f, e.getX() / v.getWidth()))
                    this@ToggleRangeTemplateBehavior.updateRange(percent, true)
                }
            }
        }

        private fun endTouch(v: View, e: MotionEvent) {
            if (!isDragging) {
                this@ToggleRangeTemplateBehavior.toggle()
            } else {
                val percent = Math.max(0.0f, Math.min(1.0f, e.getX() / v.getWidth()))
                this@ToggleRangeTemplateBehavior.endUpdateRange(percent)
            }

            initialTouchX = 0.0f
            initialTouchY = 0.0f
            isDragging = false
        }
    }
}

private class ToggleTemplateBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var template: ToggleTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    lateinit var context: Context
    lateinit var status: TextView

    override fun apply(cvh: ControlViewHolder, cws: ControlWithState) {
        this.control = cws.control!!
        this.cvh = cvh
        status = cvh.status

        status.setText(control.getStatusText())

        cvh.layout.setOnClickListener(View.OnClickListener() { toggle() })

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        template = control.getControlTemplate() as ToggleTemplate

        val checked = template.isChecked()
        val deviceType = control.getDeviceType()

        clipLayer.setLevel(if (checked) MAX_LEVEL else MIN_LEVEL)
        cvh.setEnabled(checked)
        cvh.applyRenderInfo(findRenderInfo(deviceType, checked))
    }

    fun toggle() {
        cvh.action(BooleanAction(template.getTemplateId(), !template.isChecked()))

        val nextLevel = if (template.isChecked()) MIN_LEVEL else MAX_LEVEL
        clipLayer.setLevel(nextLevel)
    }
}

internal data class RenderInfo(val iconResourceId: Int, val foreground: Int, val background: Int)

private val unknownDeviceMap = mapOf(
    false to RenderInfo(
        R.drawable.ic_light_off_gm2_24px,
        R.color.unknown_foreground,
        R.color.unknown_foreground),
    true to RenderInfo(
        R.drawable.ic_lightbulb_outline_gm2_24px,
        R.color.unknown_foreground,
        R.color.unknown_foreground)
)

private val deviceRenderMap = mapOf<Int, Map<Boolean, RenderInfo>>(
    DeviceTypes.TYPE_UNKNOWN to unknownDeviceMap,
    DeviceTypes.TYPE_LIGHT to mapOf(
        false to RenderInfo(
            R.drawable.ic_light_off_gm2_24px,
            R.color.light_foreground,
            R.color.light_background),
        true to RenderInfo(
            R.drawable.ic_lightbulb_outline_gm2_24px,
            R.color.light_foreground,
            R.color.light_background)
    ),
    DeviceTypes.TYPE_THERMOSTAT to mapOf(
        false to RenderInfo(
            R.drawable.ic_device_thermostat_gm2_24px,
            R.color.light_foreground,
            R.color.light_background),
        true to RenderInfo(
            R.drawable.ic_device_thermostat_gm2_24px,
            R.color.light_foreground,
            R.color.light_background)
    ),
    DeviceTypes.TYPE_CAMERA to mapOf(
        false to RenderInfo(
            R.drawable.ic_videocam_gm2_24px,
            R.color.light_foreground,
            R.color.light_background),
        true to RenderInfo(
            R.drawable.ic_videocam_gm2_24px,
            R.color.light_foreground,
            R.color.light_background)
    ),
    DeviceTypes.TYPE_LOCK to mapOf(
        false to RenderInfo(
            R.drawable.ic_lock_open_gm2_24px,
            R.color.lock_foreground,
            R.color.lock_background),
        true to RenderInfo(
            R.drawable.ic_lock_gm2_24px,
            R.color.lock_foreground,
            R.color.lock_background)
    ),
    DeviceTypes.TYPE_SWITCH to mapOf(
        false to RenderInfo(
            R.drawable.ic_switches_gm2_24px,
            R.color.lock_foreground,
            R.color.lock_background),
        true to RenderInfo(
            R.drawable.ic_switches_gm2_24px,
            R.color.lock_foreground,
            R.color.lock_background)
    ),
    DeviceTypes.TYPE_OUTLET to mapOf(
        false to RenderInfo(
            R.drawable.ic_power_off_gm2_24px,
            R.color.lock_foreground,
            R.color.lock_background),
        true to RenderInfo(
            R.drawable.ic_power_gm2_24px,
            R.color.lock_foreground,
            R.color.lock_background)
    )
)
