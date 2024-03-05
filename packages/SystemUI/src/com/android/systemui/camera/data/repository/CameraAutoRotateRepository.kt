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

package com.android.systemui.camera.data.repository

import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

interface CameraAutoRotateRepository {
    /** @return true if camera auto rotate setting is enabled */
    fun isCameraAutoRotateSettingEnabled(userHandle: UserHandle): StateFlow<Boolean>
}

@SysUISingleton
class CameraAutoRotateRepositoryImpl
@Inject
constructor(
    private val secureSettings: SecureSettings,
    @Background private val bgCoroutineContext: CoroutineContext,
    @Application private val applicationScope: CoroutineScope,
) : CameraAutoRotateRepository {
    private val userMap = mutableMapOf<Int, StateFlow<Boolean>>()

    override fun isCameraAutoRotateSettingEnabled(userHandle: UserHandle): StateFlow<Boolean> {
        return userMap.getOrPut(userHandle.identifier) {
            secureSettings
                .observerFlow(userHandle.identifier, Settings.Secure.CAMERA_AUTOROTATE)
                .map { isAutoRotateSettingEnabled(userHandle.identifier) }
                .onStart { emit(isAutoRotateSettingEnabled(userHandle.identifier)) }
                .flowOn(bgCoroutineContext)
                .stateIn(applicationScope, SharingStarted.WhileSubscribed(), false)
        }
    }

    private fun isAutoRotateSettingEnabled(userId: Int) =
        secureSettings.getIntForUser(SETTING_NAME, DISABLED, userId) == ENABLED

    private companion object {
        const val SETTING_NAME = Settings.Secure.CAMERA_AUTOROTATE
        const val DISABLED = 0
        const val ENABLED = 1
    }
}
