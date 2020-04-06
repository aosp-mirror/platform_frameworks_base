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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.TemperatureControlTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.R

import kotlin.reflect.KClass

private const val UPDATE_DELAY_IN_MILLIS = 3000L
private const val ALPHA_ENABLED = (255.0 * 0.2).toInt()
private const val ALPHA_DISABLED = 255

class ControlViewHolder(
    val layout: ViewGroup,
    val controlsController: ControlsController,
    val uiExecutor: DelayableExecutor,
    val bgExecutor: DelayableExecutor
) {
    val icon: ImageView = layout.requireViewById(R.id.icon)
    val status: TextView = layout.requireViewById(R.id.status)
    val title: TextView = layout.requireViewById(R.id.title)
    val subtitle: TextView = layout.requireViewById(R.id.subtitle)
    val context: Context = layout.getContext()
    val clipLayer: ClipDrawable
    lateinit var cws: ControlWithState
    var cancelUpdate: Runnable? = null
    var behavior: Behavior? = null
    var lastAction: ControlAction? = null

    init {
        val ld = layout.getBackground() as LayerDrawable
        ld.mutate()
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer) as ClipDrawable
        // needed for marquee to start
        status.setSelected(true)
    }

    fun bindData(cws: ControlWithState) {
        this.cws = cws

        cancelUpdate?.run()

        val (controlStatus, template) = cws.control?.let {
            title.setText(it.getTitle())
            subtitle.setText(it.getSubtitle())
            Pair(it.getStatus(), it.getControlTemplate())
        } ?: run {
            title.setText(cws.ci.controlTitle)
            subtitle.setText(cws.ci.controlSubtitle)
            Pair(Control.STATUS_UNKNOWN, ControlTemplate.NO_TEMPLATE)
        }

        cws.control?.let {
            layout.setClickable(true)
            layout.setOnLongClickListener(View.OnLongClickListener() {
                ControlActionCoordinator.longPress(this@ControlViewHolder)
                true
            })
        }

        val clazz = findBehavior(controlStatus, template)
        if (behavior == null || behavior!!::class != clazz) {
            // Behavior changes can signal a change in template from the app or
            // first time setup
            behavior = clazz.java.newInstance()
            behavior?.initialize(this)

            // let behaviors define their own, if necessary, and clear any existing ones
            layout.setAccessibilityDelegate(null)
        }

        behavior?.bind(cws)

        layout.setContentDescription("${title.text} ${subtitle.text} ${status.text}")
    }

    fun actionResponse(@ControlAction.ResponseResult response: Int) {
        // TODO: b/150931809 - handle response codes
    }

    fun setTransientStatus(tempStatus: String) {
        val previousText = status.getText()

        cancelUpdate = uiExecutor.executeDelayed({
                status.setText(previousText)
            }, UPDATE_DELAY_IN_MILLIS)

        status.setText(tempStatus)
    }

    fun action(action: ControlAction) {
        lastAction = action
        controlsController.action(cws.componentName, cws.ci, action)
    }

    private fun findBehavior(status: Int, template: ControlTemplate): KClass<out Behavior> {
        return when {
            status == Control.STATUS_UNKNOWN -> UnknownBehavior::class
            template is ToggleTemplate -> ToggleBehavior::class
            template is StatelessTemplate -> TouchBehavior::class
            template is ToggleRangeTemplate -> ToggleRangeBehavior::class
            template is TemperatureControlTemplate -> TemperatureControlBehavior::class
            else -> DefaultBehavior::class
        }
    }

    internal fun applyRenderInfo(enabled: Boolean, offset: Int = 0) {
        setEnabled(enabled)

        val deviceType = cws.control?.let { it.getDeviceType() } ?: cws.ci.deviceType
        val ri = RenderInfo.lookup(context, cws.componentName, deviceType, enabled, offset)

        val fg = context.getResources().getColorStateList(ri.foreground, context.getTheme())
        val (bg, alpha) = if (enabled) {
            Pair(ri.enabledBackground, ALPHA_ENABLED)
        } else {
            Pair(R.color.control_default_background, ALPHA_DISABLED)
        }

        status.setTextColor(fg)

        icon.setImageDrawable(ri.icon)

        // do not color app icons
        if (deviceType != DeviceTypes.TYPE_ROUTINE) {
            icon.setImageTintList(fg)
        }

        (clipLayer.getDrawable() as GradientDrawable).apply {
            setColor(context.getResources().getColor(bg, context.getTheme()))
            setAlpha(alpha)
        }
    }

    private fun setEnabled(enabled: Boolean) {
        status.setEnabled(enabled)
        icon.setEnabled(enabled)
    }
}
