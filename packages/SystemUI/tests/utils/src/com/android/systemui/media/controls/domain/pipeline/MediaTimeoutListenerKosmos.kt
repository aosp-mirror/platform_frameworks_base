/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.media.controls.domain.pipeline

import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.media.controls.util.fakeMediaControllerFactory
import com.android.systemui.media.controls.util.mediaFlags
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.util.time.systemClock

val Kosmos.mediaTimeoutListener by
    Kosmos.Fixture {
        MediaTimeoutListener(
            mediaControllerFactory = fakeMediaControllerFactory,
            bgExecutor = fakeExecutor,
            uiExecutor = fakeExecutor,
            mainExecutor = fakeExecutor,
            logger = MediaTimeoutLogger(logcatLogBuffer("MediaTimeoutLogBuffer")),
            statusBarStateController = statusBarStateController,
            systemClock = systemClock,
            mediaFlags = mediaFlags,
        )
    }
