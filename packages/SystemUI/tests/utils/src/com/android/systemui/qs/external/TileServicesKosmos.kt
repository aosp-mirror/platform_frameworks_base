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

package com.android.systemui.qs.external

import android.content.applicationContext
import android.os.fakeExecutorHandler
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.pipeline.data.repository.customTileAddedRepository
import com.android.systemui.qs.pipeline.domain.interactor.panelInteractor
import com.android.systemui.settings.userTracker
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

val Kosmos.tileServices: TileServices by
    Kosmos.Fixture {
        val qsHost: QSHost = mock { whenever(context).thenReturn(applicationContext) }
        TileServices(
            qsHost,
            { fakeExecutorHandler },
            broadcastDispatcher,
            userTracker,
            keyguardStateController,
            commandQueue,
            mock<StatusBarIconController>(),
            panelInteractor,
            tileLifecycleManagerFactory,
            customTileAddedRepository,
            fakeExecutor,
        )
    }
