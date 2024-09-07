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

package com.android.systemui.keyguard.data.repository

import android.content.Context
import android.provider.Settings
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

interface KeyguardSmartspaceRepository {
    val bcSmartspaceVisibility: StateFlow<Int>
    val isWeatherEnabled: StateFlow<Boolean>
    fun setBcSmartspaceVisibility(visibility: Int)
}

@SysUISingleton
class KeyguardSmartspaceRepositoryImpl
@Inject
constructor(
    context: Context,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    @Application private val applicationScope: CoroutineScope,
) : KeyguardSmartspaceRepository {
    private val _bcSmartspaceVisibility: MutableStateFlow<Int> = MutableStateFlow(View.GONE)
    override val bcSmartspaceVisibility: StateFlow<Int> = _bcSmartspaceVisibility.asStateFlow()
    override val isWeatherEnabled: StateFlow<Boolean> =
        secureSettings
            .observerFlow(
                names = arrayOf(Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED),
                userId = userTracker.userId,
            )
            .onStart { emit(Unit) }
            .map { getLockscreenWeatherEnabled() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = getLockscreenWeatherEnabled()
            )

    override fun setBcSmartspaceVisibility(visibility: Int) {
        _bcSmartspaceVisibility.value = visibility
    }

    private fun getLockscreenWeatherEnabled(): Boolean {
        return secureSettings.getIntForUser(
            Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED,
            1,
            userTracker.userId
        ) == 1
    }
}
