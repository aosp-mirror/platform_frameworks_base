/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.muteawait

import android.content.Context
import com.android.settingslib.media.DeviceIconUtil
import com.android.settingslib.media.LocalMediaManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.util.MediaFlags
import java.util.concurrent.Executor
import javax.inject.Inject

/** Factory class to create [MediaMuteAwaitConnectionManager] instances. */
@SysUISingleton
class MediaMuteAwaitConnectionManagerFactory @Inject constructor(
    private val mediaFlags: MediaFlags,
    private val context: Context,
    private val logger: MediaMuteAwaitLogger,
    @Main private val mainExecutor: Executor
) {
    private val deviceIconUtil = DeviceIconUtil()

    /** Creates a [MediaMuteAwaitConnectionManager]. */
    fun create(localMediaManager: LocalMediaManager): MediaMuteAwaitConnectionManager? {
        if (!mediaFlags.areMuteAwaitConnectionsEnabled()) {
            return null
        }
        return MediaMuteAwaitConnectionManager(
                mainExecutor, localMediaManager, context, deviceIconUtil, logger
        )
    }
}
