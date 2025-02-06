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
 *
 */

package com.android.systemui.communal.data.repository

import android.content.pm.UserInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Fake implementation of [CommunalPrefsRepository] */
class FakeCommunalPrefsRepository : CommunalPrefsRepository {
    private val _isCtaDismissed = MutableStateFlow<Set<UserInfo>>(emptySet())
    private val _isHubOnboardingDismissed = MutableStateFlow<Set<UserInfo>>(emptySet())
    private val _isDreamButtonTooltipDismissed = MutableStateFlow<Set<UserInfo>>(emptySet())

    override fun isCtaDismissed(user: UserInfo): Flow<Boolean> =
        _isCtaDismissed.map { it.contains(user) }

    override suspend fun setCtaDismissed(user: UserInfo) {
        _isCtaDismissed.value = _isCtaDismissed.value.toMutableSet().apply { add(user) }
    }

    override fun isHubOnboardingDismissed(user: UserInfo): Flow<Boolean> =
        _isHubOnboardingDismissed.map { it.contains(user) }

    override suspend fun setHubOnboardingDismissed(user: UserInfo) {
        _isHubOnboardingDismissed.value =
            _isHubOnboardingDismissed.value.toMutableSet().apply { add(user) }
    }

    override fun isDreamButtonTooltipDismissed(user: UserInfo): Flow<Boolean> =
        _isDreamButtonTooltipDismissed.map { it.contains(user) }

    override suspend fun setDreamButtonTooltipDismissed(user: UserInfo) {
        _isDreamButtonTooltipDismissed.value =
            _isDreamButtonTooltipDismissed.value.toMutableSet().apply { add(user) }
    }
}
