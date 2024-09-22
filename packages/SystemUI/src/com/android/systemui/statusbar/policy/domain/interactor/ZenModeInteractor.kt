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
import com.android.settingslib.notification.modes.ZenIcon
import com.android.settingslib.notification.modes.ZenIconLoader
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.shared.notifications.data.repository.NotificationSettingsRepository
import com.android.systemui.statusbar.policy.data.repository.DeviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.domain.model.ActiveZenModes
import com.android.systemui.statusbar.policy.domain.model.ZenModeInfo
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val iconLoader: ZenIconLoader,
    private val deviceProvisioningRepository: DeviceProvisioningRepository,
    private val userSetupRepository: UserSetupRepository,
) {
    val isZenAvailable: Flow<Boolean> =
        combine(
            deviceProvisioningRepository.isDeviceProvisioned,
            userSetupRepository.isUserSetUp,
        ) { isDeviceProvisioned, isUserSetUp ->
            isDeviceProvisioned && isUserSetUp
        }

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

    /**
     * Returns the special "manual DND" mode.
     *
     * This is only meant as a temporary solution for "legacy" UI pieces that handle DND
     * specifically; any new or migrated features should use modes more generally, through [modes]
     * or [activeModes].
     */
    val dndMode: Flow<ZenMode?> by lazy {
        ModesUi.assertInNewMode()
        zenModeRepository.modes.map { modes -> modes.singleOrNull { it.isManualDnd } }
    }

    /** Flow returning the currently active mode(s), if any. */
    val activeModes: Flow<ActiveZenModes> =
        modes
            .map { modes -> buildActiveZenModes(modes) }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()

    suspend fun getActiveModes() = buildActiveZenModes(zenModeRepository.getModes())

    private suspend fun buildActiveZenModes(modes: List<ZenMode>): ActiveZenModes {
        val activeModesList =
            modes.filter { mode -> mode.isActive }.sortedWith(ZenMode.PRIORITIZING_COMPARATOR)
        val mainActiveMode =
            activeModesList.firstOrNull()?.let { ZenModeInfo(it.name, getModeIcon(it)) }

        return ActiveZenModes(activeModesList.map { m -> m.name }, mainActiveMode)
    }

    val mainActiveMode: Flow<ZenModeInfo?> =
        activeModes.map { a -> a.mainMode }.distinctUntilChanged()

    suspend fun getModeIcon(mode: ZenMode): ZenIcon {
        return iconLoader.getIcon(context, mode).await()
    }

    fun activateMode(zenMode: ZenMode) {
        if (zenMode.isManualDnd) {
            val duration =
                when (zenDuration) {
                    ZEN_DURATION_PROMPT -> {
                        Log.e(
                            TAG,
                            "Interactor cannot handle showing the zen duration prompt. " +
                                "Please use EnableZenModeDialog when this setting is active.",
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
