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

package com.android.systemui.statusbar.policy.domain.interactor

import android.provider.Settings
import com.android.settingslib.statusbar.notification.data.repository.ZenModeRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * An interactor that performs business logic related to the status and configuration of Zen Mode
 * (or Do Not Disturb/DND Mode).
 */
class ZenModeInteractor @Inject constructor(repository: ZenModeRepository) {
    val isZenModeEnabled: Flow<Boolean> =
        repository.globalZenMode
            .map {
                when (it ?: Settings.Global.ZEN_MODE_OFF) {
                    Settings.Global.ZEN_MODE_ALARMS -> true
                    Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS -> true
                    Settings.Global.ZEN_MODE_NO_INTERRUPTIONS -> true
                    Settings.Global.ZEN_MODE_OFF -> false
                    else -> false
                }
            }
            .distinctUntilChanged()

    val areNotificationsHiddenInShade: Flow<Boolean> =
        combine(isZenModeEnabled, repository.consolidatedNotificationPolicy) { dndEnabled, policy ->
                if (!dndEnabled) {
                    false
                } else {
                    val showInNotificationList = policy?.showInNotificationList() ?: true
                    !showInNotificationList
                }
            }
            .distinctUntilChanged()
}
