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
import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlTemplate
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

const val MIN_LEVEL = 0
const val MAX_LEVEL = 10000
private const val UPDATE_DELAY_IN_MILLIS = 3000L

class ControlViewHolder(
    val layout: ViewGroup,
    val controlsController: ControlsController,
    val uiExecutor: DelayableExecutor
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
    var cancelUpdate: Runnable? = null

    init {
        val ld = layout.getBackground() as LayerDrawable
        ld.mutate()
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer) as ClipDrawable
        gd = clipLayer.getDrawable() as GradientDrawable
    }

    fun bindData(cws: ControlWithState) {
        this.cws = cws

        cancelUpdate?.run()

        val (status, template) = cws.control?.let {
            title.setText(it.getTitle())
            subtitle.setText(it.getSubtitle())
            Pair(it.getStatus(), it.getControlTemplate())
        } ?: run {
            title.setText(cws.ci.controlTitle)
            subtitle.setText("")
            Pair(Control.STATUS_UNKNOWN, ControlTemplate.NO_TEMPLATE)
        }

        cws.control?.let { c ->
            layout.setOnLongClickListener(View.OnLongClickListener() {
                val closeDialog = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                context.sendBroadcast(closeDialog)

                c.getAppIntent().send()
                true
            })
        }

        findBehavior(status, template).apply(this, cws)
    }

    fun actionResponse(@ControlAction.ResponseResult response: Int) {
        val text = when (response) {
            ControlAction.RESPONSE_OK -> "Success"
            ControlAction.RESPONSE_FAIL -> "Error"
            else -> ""
        }

        if (!text.isEmpty()) {
            val previousText = status.getText()
            val previousTextExtra = statusExtra.getText()

            cancelUpdate = uiExecutor.executeDelayed({
                    status.setText(previousText)
                    statusExtra.setText(previousTextExtra)
                }, UPDATE_DELAY_IN_MILLIS)

            status.setText(text)
            statusExtra.setText("")
        }
    }

    fun action(action: ControlAction) {
        controlsController.action(cws.ci, action)
    }

    private fun findBehavior(status: Int, template: ControlTemplate): Behavior {
        return when {
            status == Control.STATUS_UNKNOWN -> UnknownBehavior()
            template is ToggleTemplate -> ToggleBehavior()
            template is ToggleRangeTemplate -> ToggleRangeBehavior()
            template is TemperatureControlTemplate -> TemperatureControlBehavior()
            else -> {
                object : Behavior {
                    override fun apply(cvh: ControlViewHolder, cws: ControlWithState) {
                        cvh.status.setText(cws.control?.getStatusText())
                        cvh.applyRenderInfo(RenderInfo.lookup(cws.ci.deviceType, false))
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
