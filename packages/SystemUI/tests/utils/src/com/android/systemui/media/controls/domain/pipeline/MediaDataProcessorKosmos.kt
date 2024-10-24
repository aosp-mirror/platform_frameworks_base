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

import android.app.smartspace.SmartspaceManager
import android.content.applicationContext
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.media.controls.data.repository.mediaDataRepository
import com.android.systemui.media.controls.shared.mediaLogger
import com.android.systemui.media.controls.shared.model.SmartspaceMediaDataProvider
import com.android.systemui.media.controls.util.fakeMediaControllerFactory
import com.android.systemui.media.controls.util.mediaFlags
import com.android.systemui.media.controls.util.mediaUiEventLogger
import com.android.systemui.plugins.activityStarter
import com.android.systemui.util.Utils
import com.android.systemui.util.settings.fakeSettings
import com.android.systemui.util.time.systemClock

val Kosmos.mediaDataProcessor by
    Kosmos.Fixture {
        MediaDataProcessor(
            context = applicationContext,
            applicationScope = applicationCoroutineScope,
            backgroundDispatcher = testDispatcher,
            backgroundExecutor = fakeExecutor,
            uiExecutor = fakeExecutor,
            foregroundExecutor = fakeExecutor,
            mainDispatcher = testDispatcher,
            mediaControllerFactory = fakeMediaControllerFactory,
            broadcastDispatcher = broadcastDispatcher,
            dumpManager = dumpManager,
            activityStarter = activityStarter,
            smartspaceMediaDataProvider = SmartspaceMediaDataProvider(),
            useMediaResumption = Utils.useMediaResumption(applicationContext),
            useQsMediaPlayer = Utils.useQsMediaPlayer(applicationContext),
            systemClock = systemClock,
            secureSettings = fakeSettings,
            mediaFlags = mediaFlags,
            logger = mediaUiEventLogger,
            smartspaceManager = SmartspaceManager(applicationContext),
            keyguardUpdateMonitor = keyguardUpdateMonitor,
            mediaDataRepository = mediaDataRepository,
            mediaDataLoader = { mediaDataLoader },
            mediaLogger = mediaLogger,
        )
    }
