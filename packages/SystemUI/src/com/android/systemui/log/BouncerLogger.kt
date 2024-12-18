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

package com.android.systemui.log

import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.BouncerLog
import javax.inject.Inject

private const val TAG = "BouncerLog"

/**
 * Helper class for logging for classes in the [com.android.systemui.keyguard.bouncer] package.
 *
 * To enable logcat echoing for an entire buffer:
 * ```
 *   adb shell settings put global systemui/buffer/BouncerLog <logLevel>
 *
 * ```
 */
@SysUISingleton
class BouncerLogger @Inject constructor(@BouncerLog private val buffer: LogBuffer) {
    fun startBouncerMessageInteractor() {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            "Starting BouncerMessageInteractor.bouncerMessage collector"
        )
    }

    fun bouncerMessageUpdated(bouncerMsg: BouncerMessageModel?) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = bouncerMsg?.message?.messageResId ?: -1
                str1 = bouncerMsg?.message?.message
                int2 = bouncerMsg?.secondaryMessage?.messageResId ?: -1
                str2 = bouncerMsg?.secondaryMessage?.message
            },
            { "Bouncer message update received: $int1, $str1, $int2, $str2" }
        )
    }

    fun bindingBouncerMessageView() {
        buffer.log(TAG, LogLevel.DEBUG, "Binding BouncerMessageView")
    }

    fun interestedStateChanged(whatChanged: String, newValue: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = whatChanged
                bool1 = newValue
            },
            { "state changed: $str1: $bool1" }
        )
    }
}
