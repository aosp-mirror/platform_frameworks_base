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
        isRemoteInputFound: Boolean,
        reason: String,
        notificationStyle: String
    ) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = entryKey
                str2 = reason
                str3 = notificationStyle
                bool1 = isRemoteInputAlreadyActive
                bool2 = isRemoteInputFound
            },
            {
                "addRemoteInput reason:$str2 entry: $str1, style:$str3" +
                    ", isAlreadyActive: $bool1, isFound:$bool2"
            }
        )

    /** logs removeRemoteInput invocation of [RemoteInputController] */
    @JvmOverloads
    fun logRemoveRemoteInput(
        entryKey: String,
        remoteEditImeVisible: Boolean,
        remoteEditImeAnimatingAway: Boolean,
        isRemoteInputActiveForEntry: Boolean,
        isRemoteInputActive: Boolean,
        reason: String,
        notificationStyle: String
    ) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = entryKey
                str2 = reason
                str3 = notificationStyle
                bool1 = remoteEditImeVisible
                bool2 = remoteEditImeAnimatingAway
                bool3 = isRemoteInputActiveForEntry
                bool4 = isRemoteInputActive
            },
            {
                "removeRemoteInput reason: $str2 entry: $str1" +
                    ", style: $str3, remoteEditImeVisible: $bool1" +
                    ", remoteEditImeAnimatingAway: $bool2, isRemoteInputActiveForEntry: $bool3" +
                    ", isRemoteInputActive: $bool4"
            }
        )

    fun logRemoteInputApplySkipped(entryKey: String, reason: String, notificationStyle: String) =
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = entryKey
                str2 = reason
                str3 = notificationStyle
            },
            {
                "removeRemoteInput[apply is skipped] reason: $str2" +
                    "for entry: $str1, style: $str3 "
            }
        )

    private companion object {
        private const val TAG = "RemoteInputControllerLog"
    }
}
