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

package com.android.keyguard.logging

import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.KeyguardTransitionAnimationLog
import javax.inject.Inject

private const val TAG = "KeyguardTransitionAnimationLog"

/**
 * Generic logger for keyguard that's wrapping [LogBuffer]. This class should be used for adding
 * temporary logs or logs for smaller classes when creating whole new [LogBuffer] wrapper might be
 * an overkill.
 */
class KeyguardTransitionAnimationLogger
@Inject
constructor(
    @KeyguardTransitionAnimationLog val buffer: LogBuffer,
) {
    @JvmOverloads
    fun logCreate(
        name: String? = null,
        start: Float,
    ) {
        if (name == null) return

        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = name
                str2 = "$start"
            },
            { "[$str1] starts at: $str2" }
        )
    }

    @JvmOverloads
    fun logTransitionStep(
        name: String? = null,
        step: TransitionStep,
        value: Float? = null,
    ) {
        if (name == null) return

        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = "[$name][${step.transitionState}]"
                str2 = "${step.value}"
                str3 = "$value"
            },
            { "$str1 transitionStep=$str2, animationValue=$str3" }
        )
    }
}
