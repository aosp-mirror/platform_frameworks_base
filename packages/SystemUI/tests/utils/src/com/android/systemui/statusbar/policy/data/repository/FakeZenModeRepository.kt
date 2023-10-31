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

package com.android.systemui.statusbar.policy.data.repository

import android.app.NotificationManager.Policy
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class FakeZenModeRepository @Inject constructor() : ZenModeRepository {
    override val zenMode: MutableStateFlow<Int> = MutableStateFlow(Settings.Global.ZEN_MODE_OFF)
    override val consolidatedNotificationPolicy: MutableStateFlow<Policy?> =
        MutableStateFlow(
            Policy(
                /* priorityCategories = */ 0,
                /* priorityCallSenders = */ 0,
                /* priorityMessageSenders = */ 0,
            )
        )

    fun setSuppressedVisualEffects(suppressedVisualEffects: Int) {
        consolidatedNotificationPolicy.value =
            Policy(
                /* priorityCategories = */ 0,
                /* priorityCallSenders = */ 0,
                /* priorityMessageSenders = */ 0,
                /* suppressedVisualEffects = */ suppressedVisualEffects,
            )
    }
}

@Module
interface FakeZenModeRepositoryModule {
    @Binds fun bindFake(fake: FakeZenModeRepository): ZenModeRepository
}
