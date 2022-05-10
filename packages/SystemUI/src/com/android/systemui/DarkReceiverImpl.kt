/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.android.systemui.plugins.DarkIconDispatcher

class DarkReceiverImpl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
    defStyleRes: Int = 0
) : View(context, attrs, defStyle, defStyleRes), DarkIconDispatcher.DarkReceiver {

    private val dualToneHandler = DualToneHandler(context)

    init {
        onDarkChanged(ArrayList<Rect>(), 1f, DarkIconDispatcher.DEFAULT_ICON_TINT)
    }

    override fun onDarkChanged(areas: ArrayList<Rect>?, darkIntensity: Float, tint: Int) {
        val intensity = if (DarkIconDispatcher.isInAreas(areas, this)) darkIntensity else 0f
        setBackgroundColor(dualToneHandler.getSingleColor(intensity))
    }
}