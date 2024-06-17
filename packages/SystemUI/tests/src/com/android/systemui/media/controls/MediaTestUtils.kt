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

package com.android.systemui.media.controls

import com.android.internal.logging.InstanceId
import com.android.systemui.media.controls.shared.model.MediaData

class MediaTestUtils {
    companion object {
        val emptyMediaData =
            MediaData(
                userId = 0,
                initialized = true,
                app = null,
                appIcon = null,
                artist = null,
                song = null,
                artwork = null,
                actions = emptyList(),
                actionsToShowInCompact = emptyList(),
                packageName = "",
                token = null,
                clickIntent = null,
                device = null,
                active = true,
                resumeAction = null,
                isPlaying = false,
                instanceId = InstanceId.fakeInstanceId(-1),
                appUid = -1
            )
    }
}
