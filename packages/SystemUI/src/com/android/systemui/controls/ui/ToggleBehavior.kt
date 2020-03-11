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
import android.view.View
import android.service.controls.Control
import android.service.controls.templates.ToggleTemplate

import com.android.systemui.R
import com.android.systemui.controls.ui.ControlActionCoordinator.MIN_LEVEL
import com.android.systemui.controls.ui.ControlActionCoordinator.MAX_LEVEL

class ToggleBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var template: ToggleTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
        cvh.applyRenderInfo(false)

        cvh.layout.setOnClickListener(View.OnClickListener() {
            ControlActionCoordinator.toggle(cvh, template.getTemplateId(), template.isChecked())
        })
    }

    override fun bind(cws: ControlWithState) {
        this.control = cws.control!!

        cvh.status.setText(control.getStatusText())
        template = control.getControlTemplate() as ToggleTemplate

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        val checked = template.isChecked()
        clipLayer.setLevel(if (checked) MAX_LEVEL else MIN_LEVEL)
        cvh.applyRenderInfo(checked)
    }
}
