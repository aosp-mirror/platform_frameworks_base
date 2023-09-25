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
package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class FakeNotificationsKeyguardViewStateRepository @Inject constructor() :
    NotificationsKeyguardViewStateRepository {
    private val _notificationsFullyHidden = MutableStateFlow(false)
    override val areNotificationsFullyHidden: Flow<Boolean> = _notificationsFullyHidden

    private val _isPulseExpanding = MutableStateFlow(false)
    override val isPulseExpanding: Flow<Boolean> = _isPulseExpanding

    fun setNotificationsFullyHidden(fullyHidden: Boolean) {
        _notificationsFullyHidden.value = fullyHidden
    }

    fun setPulseExpanding(expanding: Boolean) {
        _isPulseExpanding.value = expanding
    }
}

@Module
interface FakeNotificationsKeyguardStateRepositoryModule {
    @Binds
    fun bindFake(
        fake: FakeNotificationsKeyguardViewStateRepository
    ): NotificationsKeyguardViewStateRepository
}
