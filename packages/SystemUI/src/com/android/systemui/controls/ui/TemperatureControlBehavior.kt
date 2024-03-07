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

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.TemperatureControlTemplate

import com.android.systemui.res.R
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MIN_LEVEL
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MAX_LEVEL

class TemperatureControlBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    var subBehavior: Behavior? = null

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
    }

    override fun bind(cws: ControlWithState, colorOffset: Int) {
        this.control = cws.control!!

        cvh.setStatusText(control.getStatusText())

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        val template = control.getControlTemplate() as TemperatureControlTemplate
        val activeMode = template.getCurrentActiveMode()
        val subTemplate = template.getTemplate()
        if (subTemplate == ControlTemplate.getNoTemplateObject() ||
            subTemplate == ControlTemplate.getErrorTemplate()) {
            // No sub template is specified, apply a default look with basic touch interaction.
            // Treat an error as no template.
            val enabled = activeMode != 0 && activeMode != TemperatureControlTemplate.MODE_OFF
            clipLayer.setLevel(if (enabled) MAX_LEVEL else MIN_LEVEL)
            cvh.applyRenderInfo(enabled, activeMode)

            cvh.layout.setOnClickListener { _ ->
                cvh.controlActionCoordinator.touch(cvh, template.getTemplateId(), control)
            }
        } else {
            // A sub template has been specified, use this as the default behavior for user
            // interactions (touch, range)
            subBehavior = cvh.bindBehavior(
                subBehavior,
                cvh.findBehaviorClass(
                    control.status,
                    subTemplate,
                    control.deviceType
                ),
                activeMode
            )
        }
    }
}
