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

import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.templates.ThumbnailTemplate

import com.android.systemui.R
import com.android.systemui.controls.ui.ControlActionCoordinator.MAX_LEVEL

/**
 * Used for controls that cannot be interacted with. Information is presented to the user
 * but no actions can be taken. If using a ThumbnailTemplate, the background image will
 * be changed.
 */
class StaticBehavior() : Behavior {
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
    }

    override fun bind(cws: ControlWithState) {
        this.control = cws.control!!

        cvh.status.setText(control.getStatusText())

        val ld = cvh.layout.getBackground() as LayerDrawable
        val clipLayer = ld.findDrawableByLayerId(R.id.clip_layer) as ClipDrawable

        clipLayer.setLevel(MAX_LEVEL)
        cvh.setEnabled(true)
        cvh.applyRenderInfo(RenderInfo.lookup(control.getDeviceType(), true))

        val template = control.getControlTemplate()
        if (template is ThumbnailTemplate) {
            cvh.bgExecutor.execute {
                // clear the default tinting in favor of only using alpha
                val drawable = template.getThumbnail().loadDrawable(cvh.context)
                drawable.setTintList(null)
                drawable.setAlpha((0.45 * 255).toInt())
                cvh.uiExecutor.execute {
                    val radius = cvh.context.getResources()
                            .getDimensionPixelSize(R.dimen.control_corner_radius).toFloat()
                    clipLayer.setDrawable(CornerDrawable(drawable, radius))
                }
            }
        }
    }
}
