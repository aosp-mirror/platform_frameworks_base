/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard

import android.view.ViewGroup
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import javax.inject.Inject

/**
 * Translates items away/towards the hinge when the device is opened/closed.
 */
@SysUIUnfoldScope
class KeyguardUnfoldTransition @Inject constructor(
    val unfoldProgressProvider: UnfoldTransitionProgressProvider
) {
    init {
        unfoldProgressProvider.addCallback(
            object : TransitionProgressListener {
                override fun onTransitionStarted() {
                }

                override fun onTransitionProgress(progress: Float) {
                }

                override fun onTransitionFinished() {
                }
            }
        )
    }

    fun setup(parent: ViewGroup) {
    }
}
