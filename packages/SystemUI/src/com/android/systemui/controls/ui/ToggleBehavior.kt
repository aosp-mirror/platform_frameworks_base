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
import android.service.controls.templates.TemperatureControlTemplate
import android.service.controls.templates.ToggleTemplate
import android.util.Log
import android.view.View
import com.android.systemui.R
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MAX_LEVEL

class ToggleBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var template: ToggleTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh

        cvh.layout.setOnClickListener(View.OnClickListener() {
            cvh.controlActionCoordinator.toggle(cvh, template.getTemplateId(), template.isChecked())
        })
    }

    override fun bind(cws: ControlWithState, colorOffset: Int) {
        this.control = cws.control!!

        cvh.setStatusText(control.getStatusText())
        val controlTemplate = control.getControlTemplate()
        template = when (controlTemplate) {
            is ToggleTemplate -> controlTemplate
            is TemperatureControlTemplate -> controlTemplate.getTemplate() as ToggleTemplate
            else -> {
                Log.e(ControlsUiController.TAG, "Unsupported template type: $controlTemplate")
                return
            }
        }

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)
        clipLayer.level = MAX_LEVEL

        val checked = template.isChecked()
        cvh.applyRenderInfo(checked, colorOffset)
    }
}
