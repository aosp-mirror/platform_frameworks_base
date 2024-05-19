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

package com.android.systemui.accessibility.data.repository

import android.hardware.display.ColorDisplayManager
import android.hardware.display.NightDisplayListener
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.accessibility.data.model.NightDisplayChangeEvent
import com.android.systemui.accessibility.data.model.NightDisplayState
import com.android.systemui.dagger.NightDisplayListenerModule
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.user.utils.UserScopedService
import com.android.systemui.util.kotlin.isLocationEnabledFlow
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.time.LocalTime
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class NightDisplayRepository
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    @Application private val scope: CoroutineScope,
    private val globalSettings: GlobalSettings,
    private val secureSettings: SecureSettings,
    private val nightDisplayListenerBuilder: NightDisplayListenerModule.Builder,
    private val colorDisplayManagerUserScopedService: UserScopedService<ColorDisplayManager>,
    private val locationController: LocationController,
) {
    private val stateFlowUserMap = mutableMapOf<Int, Flow<NightDisplayState>>()

    fun nightDisplayState(user: UserHandle): Flow<NightDisplayState> =
        stateFlowUserMap.getOrPut(user.identifier) {
            return merge(
                    colorDisplayManagerChangeEventFlow(user),
                    shouldForceAutoMode(user).map {
                        NightDisplayChangeEvent.OnForceAutoModeChanged(it)
                    },
                    locationController.isLocationEnabledFlow().map {
                        NightDisplayChangeEvent.OnLocationEnabledChanged(it)
                    }
                )
                .scan(initialState(user)) { state, event ->
                    when (event) {
                        is NightDisplayChangeEvent.OnActivatedChanged ->
                            state.copy(isActivated = event.isActivated)
                        is NightDisplayChangeEvent.OnAutoModeChanged ->
                            state.copy(autoMode = event.autoMode)
                        is NightDisplayChangeEvent.OnCustomStartTimeChanged ->
                            state.copy(startTime = event.startTime)
                        is NightDisplayChangeEvent.OnCustomEndTimeChanged ->
                            state.copy(endTime = event.endTime)
                        is NightDisplayChangeEvent.OnForceAutoModeChanged ->
                            state.copy(shouldForceAutoMode = event.shouldForceAutoMode)
                        is NightDisplayChangeEvent.OnLocationEnabledChanged ->
                            state.copy(locationEnabled = event.locationEnabled)
                    }
                }
                .conflate()
                .onStart { emit(initialState(user)) }
                .flowOn(bgCoroutineContext)
                .stateIn(scope, SharingStarted.WhileSubscribed(), NightDisplayState())
        }

    /** Track changes in night display enabled state and its auto mode */
    private fun colorDisplayManagerChangeEventFlow(user: UserHandle) = callbackFlow {
        val nightDisplayListener = nightDisplayListenerBuilder.setUser(user.identifier).build()
        val nightDisplayCallback =
            object : NightDisplayListener.Callback {
                override fun onActivated(activated: Boolean) {
                    trySend(NightDisplayChangeEvent.OnActivatedChanged(activated))
                }

                override fun onAutoModeChanged(autoMode: Int) {
                    trySend(NightDisplayChangeEvent.OnAutoModeChanged(autoMode))
                }

                override fun onCustomStartTimeChanged(startTime: LocalTime?) {
                    trySend(NightDisplayChangeEvent.OnCustomStartTimeChanged(startTime))
                }

                override fun onCustomEndTimeChanged(endTime: LocalTime?) {
                    trySend(NightDisplayChangeEvent.OnCustomEndTimeChanged(endTime))
                }
            }
        nightDisplayListener.setCallback(nightDisplayCallback)
        awaitClose { nightDisplayListener.setCallback(null) }
    }

    /** @return true when the option to force auto mode is available and a value has not been set */
    private fun shouldForceAutoMode(userHandle: UserHandle): Flow<Boolean> =
        combine(isForceAutoModeAvailable, isDisplayAutoModeRawNotSet(userHandle)) {
            isForceAutoModeAvailable,
            isDisplayAutoModeRawNotSet,
            ->
            isForceAutoModeAvailable && isDisplayAutoModeRawNotSet
        }

    private val isForceAutoModeAvailable: Flow<Boolean> =
        globalSettings
            .observerFlow(IS_FORCE_AUTO_MODE_AVAILABLE_SETTING_NAME)
            .onStart { emit(Unit) }
            .map {
                globalSettings.getString(IS_FORCE_AUTO_MODE_AVAILABLE_SETTING_NAME) ==
                    NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE
            }
            .distinctUntilChanged()

    /** Inspired by [ColorDisplayService.getNightDisplayAutoModeRawInternal] */
    private fun isDisplayAutoModeRawNotSet(userHandle: UserHandle): Flow<Boolean> =
        if (userHandle.identifier == UserHandle.USER_NULL) {
                flowOf(IS_AUTO_MODE_RAW_NOT_SET_DEFAULT)
            } else {
                secureSettings
                    .observerFlow(userHandle.identifier, DISPLAY_AUTO_MODE_RAW_SETTING_NAME)
                    .onStart { emit(Unit) }
                    .map { isNightDisplayAutoModeRawSettingNotSet(userHandle.identifier) }
            }
            .distinctUntilChanged()

    suspend fun setNightDisplayAutoMode(autoMode: Int, user: UserHandle) {
        withContext(bgCoroutineContext) {
            colorDisplayManagerUserScopedService.forUser(user).nightDisplayAutoMode = autoMode
        }
    }

    suspend fun setNightDisplayActivated(activated: Boolean, user: UserHandle) {
        withContext(bgCoroutineContext) {
            colorDisplayManagerUserScopedService.forUser(user).isNightDisplayActivated = activated
        }
    }

    private fun initialState(user: UserHandle): NightDisplayState {
        val colorDisplayManager = colorDisplayManagerUserScopedService.forUser(user)
        return NightDisplayState(
            colorDisplayManager.nightDisplayAutoMode,
            colorDisplayManager.isNightDisplayActivated,
            colorDisplayManager.nightDisplayCustomStartTime,
            colorDisplayManager.nightDisplayCustomEndTime,
            globalSettings.getString(IS_FORCE_AUTO_MODE_AVAILABLE_SETTING_NAME) ==
                NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE &&
                isNightDisplayAutoModeRawSettingNotSet(user.identifier),
            locationController.isLocationEnabled,
        )
    }

    private fun isNightDisplayAutoModeRawSettingNotSet(userId: Int): Boolean {
        return secureSettings.getIntForUser(
            DISPLAY_AUTO_MODE_RAW_SETTING_NAME,
            NIGHT_DISPLAY_AUTO_MODE_RAW_NOT_SET,
            userId
        ) == NIGHT_DISPLAY_AUTO_MODE_RAW_NOT_SET
    }

    private companion object {
        const val NIGHT_DISPLAY_AUTO_MODE_RAW_NOT_SET = -1
        const val NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE = "1"
        const val IS_AUTO_MODE_RAW_NOT_SET_DEFAULT = true
        const val IS_FORCE_AUTO_MODE_AVAILABLE_SETTING_NAME =
            Settings.Global.NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE
        const val DISPLAY_AUTO_MODE_RAW_SETTING_NAME = Settings.Secure.NIGHT_DISPLAY_AUTO_MODE
    }
}
