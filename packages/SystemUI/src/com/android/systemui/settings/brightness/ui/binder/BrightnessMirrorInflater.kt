/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.settings.brightness.ui.binder

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.android.systemui.res.R
import com.android.systemui.settings.brightness.BrightnessSliderController

object BrightnessMirrorInflater {

    fun inflate(
        context: Context,
        sliderControllerFactory: BrightnessSliderController.Factory,
    ): Pair<View, BrightnessSliderController> {
        val frame =
            (LayoutInflater.from(context).inflate(R.layout.brightness_mirror_container, null)
                    as ViewGroup)
                .apply { isVisible = true }
        val sliderController = sliderControllerFactory.create(context, frame)
        sliderController.init()
        frame.addView(
            sliderController.rootView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return frame to sliderController
    }
}
