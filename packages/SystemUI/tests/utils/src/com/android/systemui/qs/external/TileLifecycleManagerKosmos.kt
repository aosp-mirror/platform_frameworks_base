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

import android.app.activityManager
import android.content.applicationContext
import android.os.fakeExecutorHandler
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.packageManagerAdapterFacade
import com.android.systemui.util.mockito.mock

val Kosmos.tileLifecycleManagerFactory: TileLifecycleManager.Factory by
    Kosmos.Fixture {
        TileLifecycleManager.Factory { intent, userHandle ->
            TileLifecycleManager(
                fakeExecutorHandler,
                applicationContext,
                tileServices,
                packageManagerAdapterFacade.packageManagerAdapter,
                broadcastDispatcher,
                intent,
                userHandle,
                activityManager,
                mock(),
                fakeExecutor,
            )
        }
    }
