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

import android.content.Context
import android.provider.Settings
import android.provider.Settings.Secure.ZEN_DURATION_FOREVER
import android.provider.Settings.Secure.ZEN_DURATION_PROMPT
import android.util.Log
import androidx.concurrent.futures.await
import com.android.settingslib.notification.data.repository.ZenModeRepository
import com.android.settingslib.notification.modes.ZenIconLoader
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.shared.notifications.data.repository.NotificationSettingsRepository
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * An interactor that performs business logic related to the status and configuration of Zen Mode
 * (or Do Not Disturb/DND Mode).
 */
class ZenModeInteractor
@Inject
constructor(
    private val context: Context,
    private val zenModeRepository: ZenModeRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
) {
    private val iconLoader: ZenIconLoader = ZenIconLoader.getInstance()

    val isZenModeEnabled: Flow<Boolean> =
        zenModeRepository.globalZenMode
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
        combine(isZenModeEnabled, zenModeRepository.consolidatedNotificationPolicy) {
                dndEnabled,
                policy ->
                if (!dndEnabled) {
                    false
                } else {
                    val showInNotificationList = policy?.showInNotificationList() ?: true
                    !showInNotificationList
                }
            }
            .distinctUntilChanged()

    val modes: Flow<List<ZenMode>> = zenModeRepository.modes

    val activeModes: Flow<List<ZenMode>> =
        modes.map { modes -> modes.filter { mode -> mode.isActive } }.distinctUntilChanged()

    /** Flow returning the most prioritized of the active modes, if any. */
    val mainActiveMode: Flow<ZenMode?> =
        activeModes.map { modes -> getMainActiveMode(modes) }.distinctUntilChanged()

    /**
     * Given the list of modes (which may include zero or more currently active modes), returns the
     * most prioritized of the active modes, if any.
     */
    private fun getMainActiveMode(modes: List<ZenMode>): ZenMode? {
        return modes.sortedWith(ZenMode.PRIORITIZING_COMPARATOR).firstOrNull { it.isActive }
    }

    suspend fun getModeIcon(mode: ZenMode): Icon {
        return iconLoader.getIcon(context, mode).await().drawable().asIcon()
    }

    /**
     * Given the list of modes (which may include zero or more currently active modes), returns an
     * icon representing the active mode, if any (or, if multiple modes are active, to the most
     * prioritized one). This icon is suitable for use in the status bar or lockscreen (uses the
     * standard DND icon for implicit modes, instead of the launcher icon of the associated
     * package).
     */
    suspend fun getActiveModeIcon(modes: List<ZenMode>): Icon? {
        return getMainActiveMode(modes)?.let { m -> getModeIcon(m) }
    }

    fun activateMode(zenMode: ZenMode) {
        if (zenMode.isManualDnd) {
            val duration =
                when (zenDuration) {
                    ZEN_DURATION_PROMPT -> {
                        Log.e(
                            TAG,
                            "Interactor cannot handle showing the zen duration prompt. " +
                                "Please use EnableZenModeDialog when this setting is active."
                        )
                        null
                    }
                    ZEN_DURATION_FOREVER -> null
                    else -> Duration.ofMinutes(zenDuration.toLong())
                }

            zenModeRepository.activateMode(zenMode, duration)
        } else {
            zenModeRepository.activateMode(zenMode)
        }
    }

    fun deactivateMode(zenMode: ZenMode) {
        zenModeRepository.deactivateMode(zenMode)
    }

    private val zenDuration
        get() = notificationSettingsRepository.zenDuration.value

    fun shouldAskForZenDuration(mode: ZenMode): Boolean =
        mode.isManualDnd && (zenDuration == ZEN_DURATION_PROMPT)

    companion object {
        private const val TAG = "ZenModeInteractor"
    }
}
