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

package com.android.systemui.media.controls.domain.pipeline.interactor

import com.android.systemui.activityIntentHelper
import com.android.systemui.bluetooth.mockBroadcastDialogController
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.mediaDataProcessor
import com.android.systemui.media.controls.util.mediaInstanceId
import com.android.systemui.media.mediaOutputDialogManager
import com.android.systemui.plugins.activityStarter
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.policy.keyguardStateController

val Kosmos.mediaControlInteractor by
    Kosmos.Fixture {
        MediaControlInteractor(
            instanceId = mediaInstanceId,
            repository = mediaFilterRepository,
            mediaDataProcessor = mediaDataProcessor,
            keyguardStateController = keyguardStateController,
            activityStarter = activityStarter,
            activityIntentHelper = activityIntentHelper,
            lockscreenUserManager = notificationLockscreenUserManager,
            mediaOutputDialogManager = mediaOutputDialogManager,
            broadcastDialogController = mockBroadcastDialogController,
        )
    }
