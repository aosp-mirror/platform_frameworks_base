/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.content.Context
import android.util.AttributeSet
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** View corresponding with udfps_keyguard_view.xml */
@ExperimentalCoroutinesApi
class UdfpsKeyguardView(
    context: Context,
    attrs: AttributeSet?,
) :
    UdfpsAnimationView(
        context,
        attrs,
    ) {
    private val fingerprintDrawablePlaceHolder = UdfpsFpDrawable(context)
    private var visible = false

    override fun calculateAlpha(): Int {
        return if (mPauseAuth) {
            0
        } else 255 // ViewModels handle animating alpha values
    }

    override fun getDrawable(): UdfpsDrawable {
        return fingerprintDrawablePlaceHolder
    }

    fun isVisible(): Boolean {
        return visible
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        isPauseAuth = !visible
    }
}
