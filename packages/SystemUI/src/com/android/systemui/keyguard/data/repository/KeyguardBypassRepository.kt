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

package com.android.systemui.keyguard.data.repository

import android.annotation.IntDef
import android.content.res.Resources
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.keyguard.shared.model.DevicePosture.UNKNOWN
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.settings.repository.UserAwareSecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@SysUISingleton
class KeyguardBypassRepository
@Inject
constructor(
    @Main resources: Resources,
    biometricSettingsRepository: BiometricSettingsRepository,
    devicePostureRepository: DevicePostureRepository,
    dumpManager: DumpManager,
    secureSettingsRepository: UserAwareSecureSettingsRepository,
    @Background backgroundDispatcher: CoroutineDispatcher,
) : FlowDumperImpl(dumpManager) {

    @get:BypassOverride
    private val bypassOverride: Int by lazy {
        resources.getInteger(R.integer.config_face_unlock_bypass_override)
    }

    private val configFaceAuthSupportedPosture: DevicePosture by lazy {
        DevicePosture.toPosture(resources.getInteger(R.integer.config_face_auth_supported_posture))
    }

    private var bypassEnabledSetting: Flow<Boolean> =
        secureSettingsRepository
            .boolSetting(
                name = Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD,
                defaultValue =
                    resources.getBoolean(
                        com.android.internal.R.bool.config_faceAuthDismissesKeyguard
                    ),
            )
            .flowOn(backgroundDispatcher)
            .dumpWhileCollecting("bypassEnabledSetting")

    private val overrideFaceBypassSetting: Flow<Boolean> =
        when (bypassOverride) {
            FACE_UNLOCK_BYPASS_ALWAYS -> flowOf(true)
            FACE_UNLOCK_BYPASS_NEVER -> flowOf(false)
            else -> bypassEnabledSetting
        }

    private val isPostureAllowedForFaceAuth: Flow<Boolean> =
        when (configFaceAuthSupportedPosture) {
            UNKNOWN -> flowOf(true)
            else ->
                devicePostureRepository.currentDevicePosture
                    .map { posture -> posture == configFaceAuthSupportedPosture }
                    .distinctUntilChanged()
        }

    /**
     * Whether bypass is available.
     *
     * Bypass is the ability to skip the lockscreen when the device is unlocked using non-primary
     * authentication types like face unlock, instead of requiring the user to explicitly dismiss
     * the lockscreen by swiping after the device is already unlocked.
     *
     * "Available" refers to a combination of the user setting to skip the lockscreen being set,
     * whether hard-wired OEM-overridable configs allow the feature, whether a foldable is in the
     * right foldable posture, and other such things. It does _not_ model this based on more
     * runtime-like states of the UI.
     */
    val isBypassAvailable: Flow<Boolean> =
        combine(
                overrideFaceBypassSetting,
                biometricSettingsRepository.isFaceAuthEnrolledAndEnabled,
                isPostureAllowedForFaceAuth,
            ) {
                bypassOverride: Boolean,
                isFaceEnrolledAndEnabled: Boolean,
                isPostureAllowedForFaceAuth: Boolean ->
                bypassOverride && isFaceEnrolledAndEnabled && isPostureAllowedForFaceAuth
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("isBypassAvailable")

    @IntDef(FACE_UNLOCK_BYPASS_NO_OVERRIDE, FACE_UNLOCK_BYPASS_ALWAYS, FACE_UNLOCK_BYPASS_NEVER)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class BypassOverride

    companion object {
        private const val FACE_UNLOCK_BYPASS_NO_OVERRIDE = 0
        private const val FACE_UNLOCK_BYPASS_ALWAYS = 1
        private const val FACE_UNLOCK_BYPASS_NEVER = 2

        private const val TAG = "KeyguardBypassRepository"
    }
}
