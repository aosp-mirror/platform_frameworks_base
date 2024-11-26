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

package com.android.systemui.controls.management

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsets.Type

interface ControlsManagementActivity {
    val activity: Activity
}

fun ControlsManagementActivity.applyInsets(viewId: Int) {
    activity.requireViewById<ViewGroup>(viewId).apply {
        setOnApplyWindowInsetsListener { v: View, insets: WindowInsets ->
            v.apply {
                val paddings = insets.getInsets(Type.systemBars() or Type.displayCutout())
                setPadding(paddings.left, paddings.top, paddings.right, paddings.bottom)
            }

            WindowInsets.CONSUMED
        }
    }
}
