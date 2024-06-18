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

import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn

/**
 * Single user version of [AccessibilityQsShortcutsRepository]. It provides a similar interface as
 * [TileSpecRepository], but focusing solely on the user it was created for. It observes the changes
 * on the [Settings.Secure.ACCESSIBILITY_QS_TARGETS] for a given user
 */
class UserA11yQsShortcutsRepository
@AssistedInject
constructor(
    @Assisted private val userId: Int,
    private val secureSettings: SecureSettings,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    val targets =
        secureSettings
            .observerFlow(userId, SETTING_NAME)
            // Force an update
            .onStart { emit(Unit) }
            .map { getA11yQsShortcutTargets(userId) }
            .flowOn(backgroundDispatcher)
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                replay = 1
            )

    private fun getA11yQsShortcutTargets(userId: Int): Set<String> {
        val settingValue = secureSettings.getStringForUser(SETTING_NAME, userId) ?: ""
        return settingValue.split(SETTING_SEPARATOR).filterNot { it.isEmpty() }.toSet()
    }

    companion object {
        const val SETTING_NAME = Settings.Secure.ACCESSIBILITY_QS_TARGETS
        const val SETTING_SEPARATOR = ":"
    }

    @AssistedFactory
    interface Factory {
        fun create(
            userId: Int,
        ): UserA11yQsShortcutsRepository
    }
}
