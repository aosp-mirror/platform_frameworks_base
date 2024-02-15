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

package com.android.systemui.mediaprojection.taskswitcher.data.repository

import android.media.projection.MediaProjectionInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.os.UserHandle
import android.view.ContentRecordingSession
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

class FakeMediaProjectionManager {

    val mediaProjectionManager = mock<MediaProjectionManager>()

    private val callbacks = mutableListOf<MediaProjectionManager.Callback>()

    init {
        whenever(mediaProjectionManager.addCallback(any(), any())).thenAnswer {
            callbacks += it.arguments[0] as MediaProjectionManager.Callback
            return@thenAnswer Unit
        }
        whenever(mediaProjectionManager.removeCallback(any())).thenAnswer {
            callbacks -= it.arguments[0] as MediaProjectionManager.Callback
            return@thenAnswer Unit
        }
    }

    fun dispatchOnStart(info: MediaProjectionInfo = DEFAULT_INFO) {
        callbacks.forEach { it.onStart(info) }
    }

    fun dispatchOnStop(info: MediaProjectionInfo = DEFAULT_INFO) {
        callbacks.forEach { it.onStop(info) }
    }

    fun dispatchOnSessionSet(
        info: MediaProjectionInfo = DEFAULT_INFO,
        session: ContentRecordingSession?
    ) {
        callbacks.forEach { it.onRecordingSessionSet(info, session) }
    }

    companion object {
        fun createDisplaySession(): ContentRecordingSession =
            ContentRecordingSession.createDisplaySession(/* displayToMirror = */ 123)
        fun createSingleTaskSession(token: IBinder = Binder()): ContentRecordingSession =
            ContentRecordingSession.createTaskSession(token)

        private const val DEFAULT_PACKAGE_NAME = "com.media.projection.test"
        private val DEFAULT_USER_HANDLE = UserHandle.getUserHandleForUid(UserHandle.myUserId())
        private val DEFAULT_INFO =
            MediaProjectionInfo(
                DEFAULT_PACKAGE_NAME,
                DEFAULT_USER_HANDLE,
                /* launchCookie = */ null
            )
    }
}
