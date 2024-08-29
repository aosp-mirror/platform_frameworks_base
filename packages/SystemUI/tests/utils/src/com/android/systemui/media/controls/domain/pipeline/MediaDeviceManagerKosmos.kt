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

import android.content.applicationContext
import android.media.MediaRouter2Manager
import android.os.fakeExecutorHandler
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.media.controls.util.fakeMediaControllerFactory
import com.android.systemui.media.controls.util.localMediaManagerFactory
import com.android.systemui.media.muteawait.mediaMuteAwaitConnectionManagerFactory
import com.android.systemui.statusbar.policy.configurationController

val Kosmos.mediaDeviceManager by
    Kosmos.Fixture {
        MediaDeviceManager(
            context = applicationContext,
            controllerFactory = fakeMediaControllerFactory,
            localMediaManagerFactory = localMediaManagerFactory,
            mr2manager = { MediaRouter2Manager.getInstance(applicationContext) },
            muteAwaitConnectionManagerFactory = mediaMuteAwaitConnectionManagerFactory,
            configurationController = configurationController,
            localBluetoothManager = {
                LocalBluetoothManager.create(applicationContext, fakeExecutorHandler)
            },
            fgExecutor = fakeExecutor,
            bgExecutor = fakeExecutor,
            logger = mediaDeviceLogger,
        )
    }
