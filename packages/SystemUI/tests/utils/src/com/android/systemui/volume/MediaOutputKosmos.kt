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

package com.android.systemui.volume

import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.looper
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.mediaOutputDialogManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.data.repository.FakeLocalMediaRepository
import com.android.systemui.volume.data.repository.FakeMediaControllerRepository
import com.android.systemui.volume.panel.component.mediaoutput.data.repository.FakeLocalMediaRepositoryFactory
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputActionsInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor

val Kosmos.localMediaRepository by Kosmos.Fixture { FakeLocalMediaRepository() }
val Kosmos.localMediaRepositoryFactory by
    Kosmos.Fixture { FakeLocalMediaRepositoryFactory { localMediaRepository } }

val Kosmos.mediaOutputActionsInteractor by
    Kosmos.Fixture { MediaOutputActionsInteractor(mediaOutputDialogManager) }
val Kosmos.mediaControllerRepository by Kosmos.Fixture { FakeMediaControllerRepository() }
val Kosmos.mediaOutputInteractor by
    Kosmos.Fixture {
        MediaOutputInteractor(
            localMediaRepositoryFactory,
            packageManager.apply {
                val appInfo: ApplicationInfo = mock {
                    whenever(loadLabel(any())).thenReturn("test_label")
                }
                whenever(getApplicationInfo(any(), any<Int>())).thenReturn(appInfo)
            },
            testScope.backgroundScope,
            testScope.testScheduler,
            mediaControllerRepository,
            Handler(looper),
        )
    }

val Kosmos.mediaDeviceSessionInteractor by
    Kosmos.Fixture {
        MediaDeviceSessionInteractor(
            testScope.testScheduler,
            Handler(looper),
            mediaControllerRepository,
        )
    }
