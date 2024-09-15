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

package com.android.systemui.biometrics.data.repository

import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.SecureSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** Settings for a user. */
interface FaceUserSettingsRepository {
    /** The user's id. */
    val userId: Int

    /** If BiometricPrompt should always require confirmation (overrides app's preference). */
    val alwaysRequireConfirmationInApps: Flow<Boolean>
}

class FaceUserSettingsRepositoryImpl(
    override val userId: Int,
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
) : FaceUserSettingsRepository {

    /** Indefinitely subscribe to user preference changes. */
    fun start() {
        watch(
            FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
            _alwaysRequireConfirmationInApps,
        )
    }

    private var _alwaysRequireConfirmationInApps = MutableStateFlow(false)
    override val alwaysRequireConfirmationInApps: Flow<Boolean> =
        _alwaysRequireConfirmationInApps.asStateFlow()

    /** Defaults to use when no user is specified. */
    object Empty : FaceUserSettingsRepository {
        override val userId = -1
        override val alwaysRequireConfirmationInApps = flowOf(false)
    }

    private fun watch(
        key: String,
        toUpdate: MutableStateFlow<Boolean>,
        defaultValue: Boolean = false,
    ) = secureSettings.watch(userId, mainHandler, key, defaultValue) { v -> toUpdate.value = v }
}

private fun SecureSettings.watch(
    userId: Int,
    handler: Handler,
    key: String,
    defaultValue: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    fun fetch(): Boolean = getIntForUser(key, if (defaultValue) 1 else 0, userId) > 0

    registerContentObserverForUserSync(
        key,
        false /* notifyForDescendants */,
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = onChange(fetch())
        },
        userId
    )

    onChange(fetch())
}
