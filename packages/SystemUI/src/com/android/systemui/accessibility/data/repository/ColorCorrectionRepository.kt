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

package com.android.systemui.accessibility.data.repository

import android.os.UserHandle
import android.provider.Settings.Secure
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/** Provides data related to color correction. */
interface ColorCorrectionRepository {
    /** Observable for whether color correction is enabled */
    fun isEnabled(userHandle: UserHandle): Flow<Boolean>

    /** Sets color correction enabled state. */
    suspend fun setIsEnabled(isEnabled: Boolean, userHandle: UserHandle): Boolean
}

@SysUISingleton
class ColorCorrectionRepositoryImpl
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    @Application private val scope: CoroutineScope,
    private val secureSettings: SecureSettings,
) : ColorCorrectionRepository {

    private val userMap = mutableMapOf<Int, Flow<Boolean>>()

    override fun isEnabled(userHandle: UserHandle): Flow<Boolean> =
        userMap.getOrPut(userHandle.identifier) {
            secureSettings
                .observerFlow(userHandle.identifier, SETTING_NAME)
                .onStart { emit(Unit) }
                .map {
                    secureSettings.getIntForUser(SETTING_NAME, DISABLED, userHandle.identifier) ==
                        ENABLED
                }
                .distinctUntilChanged()
                .flowOn(bgCoroutineContext)
                .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)
        }

    override suspend fun setIsEnabled(isEnabled: Boolean, userHandle: UserHandle): Boolean =
        withContext(bgCoroutineContext) {
            secureSettings.putIntForUser(
                SETTING_NAME,
                if (isEnabled) ENABLED else DISABLED,
                userHandle.identifier
            )
        }

    companion object {
        private const val SETTING_NAME = Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED
        private const val DISABLED = 0
        private const val ENABLED = 1
    }
}
