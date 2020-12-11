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

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.service.controls.Control
import android.service.controls.templates.ThumbnailTemplate
import android.util.TypedValue

import com.android.systemui.R
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MAX_LEVEL
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MIN_LEVEL

/**
 * Supports display of static images on the background of the tile. When marked active, the title
 * and subtitle will not be visible. To be used with {@link Thumbnailtemplate} only.
 */
class ThumbnailBehavior : Behavior {
    lateinit var template: ThumbnailTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    private var shadowOffsetX: Float = 0f
    private var shadowOffsetY: Float = 0f
    private var shadowRadius: Float = 0f
    private var shadowColor: Int = 0

    private val enabled: Boolean
        get() = template.isActive()

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh

        val outValue = TypedValue()
        cvh.context.resources.getValue(R.dimen.controls_thumbnail_shadow_x, outValue, true)
        shadowOffsetX = outValue.getFloat()

        cvh.context.resources.getValue(R.dimen.controls_thumbnail_shadow_y, outValue, true)
        shadowOffsetY = outValue.getFloat()

        cvh.context.resources.getValue(R.dimen.controls_thumbnail_shadow_radius, outValue, true)
        shadowRadius = outValue.getFloat()

        shadowColor = cvh.context.resources.getColor(R.color.control_thumbnail_shadow_color)
        cvh.layout.setOnClickListener(View.OnClickListener() {
            cvh.controlActionCoordinator.touch(cvh, template.getTemplateId(), control)
        })
    }

    override fun bind(cws: ControlWithState, colorOffset: Int) {
        this.control = cws.control!!
        cvh.setStatusText(control.getStatusText())
        template = control.getControlTemplate() as ThumbnailTemplate

        val ld = cvh.layout.getBackground() as LayerDrawable
        val clipLayer = ld.findDrawableByLayerId(R.id.clip_layer) as ClipDrawable

        clipLayer.setLevel(if (enabled) MAX_LEVEL else MIN_LEVEL)

        if (template.isActive()) {
            cvh.title.visibility = View.INVISIBLE
            cvh.subtitle.visibility = View.INVISIBLE
            cvh.status.setShadowLayer(shadowOffsetX, shadowOffsetY, shadowRadius, shadowColor)

            cvh.bgExecutor.execute {
                val drawable = template.getThumbnail().loadDrawable(cvh.context)
                cvh.uiExecutor.execute {
                    val radius = cvh.context.getResources()
                        .getDimensionPixelSize(R.dimen.control_corner_radius).toFloat()
                    clipLayer.setDrawable(CornerDrawable(drawable, radius))
                    clipLayer.setColorFilter(BlendModeColorFilter(cvh.context.resources
                        .getColor(R.color.control_thumbnail_tint), BlendMode.LUMINOSITY))
                    cvh.applyRenderInfo(enabled, colorOffset)
                }
            }
        } else {
            cvh.title.visibility = View.VISIBLE
            cvh.subtitle.visibility = View.VISIBLE
            cvh.status.setShadowLayer(0f, 0f, 0f, shadowColor)
        }

        cvh.applyRenderInfo(enabled, colorOffset)
    }
}
