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

package com.android.systemui.statusbar.notification

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.dagger.NotificationRemoteInputLog
import javax.inject.Inject

/** Logger class for [RemoteInputController]. */
@SysUISingleton
class RemoteInputControllerLogger
@Inject
constructor(@NotificationRemoteInputLog private val logBuffer: LogBuffer) {

    /** logs addRemoteInput invocation of [RemoteInputController] */
    fun logAddRemoteInput(
        entryKey: String,
        isRemoteInputAlreadyActive: Boolean,
        isRemoteInputFound: Boolean
    ) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = entryKey
                bool1 = isRemoteInputAlreadyActive
                bool2 = isRemoteInputFound
            },
            { "addRemoteInput entry: $str1, isAlreadyActive: $bool1, isFound:$bool2" }
        )

    /** logs removeRemoteInput invocation of [RemoteInputController] */
    @JvmOverloads
    fun logRemoveRemoteInput(
        entryKey: String,
        remoteEditImeVisible: Boolean,
        remoteEditImeAnimatingAway: Boolean,
        isRemoteInputActive: Boolean? = null
    ) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = entryKey
                bool1 = remoteEditImeVisible
                bool2 = remoteEditImeAnimatingAway
                str2 = isRemoteInputActive?.toString() ?: "N/A"
            },
            {
                "removeRemoteInput entry: $str1, remoteEditImeVisible: $bool1" +
                    ", remoteEditImeAnimatingAway: $bool2, isActive: $str2"
            }
        )

    private companion object {
        private const val TAG = "RemoteInputControllerLog"
    }
}
