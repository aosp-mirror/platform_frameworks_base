/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar.phone

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import com.android.systemui.res.R

/**
 * Renders the bottom area of the lock-screen. Concerned primarily with the quick affordance UI
 * elements. A secondary concern is the interaction of the quick affordance elements with the
 * indication area between them, though the indication area is primarily controlled elsewhere.
 */
@Deprecated("Deprecated as part of b/278057014")
class KeyguardBottomAreaView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) :
    FrameLayout(
        context,
        attrs,
        defStyleAttr,
        defStyleRes,
    ) {
    override fun hasOverlappingRendering(): Boolean {
        return false
    }
}
