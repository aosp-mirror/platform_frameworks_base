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

import android.os.Handler
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Repository for the global state of users Face Unlock preferences.
 *
 * Largely a wrapper around [SecureSettings]'s proxy to Settings.Secure.
 */
interface FaceSettingsRepository {

    /** Get Settings for the given user [id]. */
    fun forUser(id: Int?): FaceUserSettingsRepository
}

@SysUISingleton
class FaceSettingsRepositoryImpl
@Inject
constructor(
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
) : FaceSettingsRepository {

    private val userSettings = ConcurrentHashMap<Int, FaceUserSettingsRepository>()

    override fun forUser(id: Int?): FaceUserSettingsRepository =
        if (id != null) {
            userSettings.computeIfAbsent(id) { _ ->
                FaceUserSettingsRepositoryImpl(id, mainHandler, secureSettings).also { repo ->
                    repo.start()
                }
            }
        } else {
            FaceUserSettingsRepositoryImpl.Empty
        }
}
