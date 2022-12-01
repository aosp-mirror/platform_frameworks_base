/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer.Companion.BINDINGS
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps quick affordance selections and legacy user settings in sync.
 *
 * "Legacy user settings" are user settings like: Settings > Display > Lock screen > "Show device
 * controls" Settings > Display > Lock screen > "Show wallet"
 *
 * Quick affordance selections are the ones available through the new custom lock screen experience
 * from Settings > Wallpaper & Style.
 *
 * This class keeps these in sync, mostly for backwards compatibility purposes and in order to not
 * "forget" an existing legacy user setting when the device gets updated with a version of System UI
 * that has the new customizable lock screen feature.
 *
 * The way it works is that, when [startSyncing] is called, the syncer starts coroutines to listen
 * for changes in both legacy user settings and their respective affordance selections. Whenever one
 * of each pair is changed, the other member of that pair is also updated to match. For example, if
 * the user turns on "Show device controls", we automatically select the home controls affordance
 * for the preferred slot. Conversely, when the home controls affordance is unselected by the user,
 * we set the "Show device controls" setting to "off".
 *
 * The class can be configured by updating its list of triplets in the code under [BINDINGS].
 */
@SysUISingleton
class KeyguardQuickAffordanceLegacySettingSyncer
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val secureSettings: SecureSettings,
    private val selectionsManager: KeyguardQuickAffordanceLocalUserSelectionManager,
) {
    companion object {
        private val BINDINGS =
            listOf(
                Binding(
                    settingsKey = Settings.Secure.LOCKSCREEN_SHOW_CONTROLS,
                    slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                    affordanceId = BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS,
                ),
                Binding(
                    settingsKey = Settings.Secure.LOCKSCREEN_SHOW_WALLET,
                    slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                    affordanceId = BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET,
                ),
                Binding(
                    settingsKey = Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER,
                    slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                    affordanceId = BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER,
                ),
            )
    }

    fun startSyncing(
        bindings: List<Binding> = BINDINGS,
    ): Job {
        return scope.launch { bindings.forEach { binding -> startSyncing(this, binding) } }
    }

    private fun startSyncing(
        scope: CoroutineScope,
        binding: Binding,
    ) {
        secureSettings
            .observerFlow(
                names = arrayOf(binding.settingsKey),
                userId = UserHandle.USER_ALL,
            )
            .map {
                isSet(
                    settingsKey = binding.settingsKey,
                )
            }
            .distinctUntilChanged()
            .onEach { isSet ->
                if (isSelected(binding.affordanceId) != isSet) {
                    if (isSet) {
                        select(
                            slotId = binding.slotId,
                            affordanceId = binding.affordanceId,
                        )
                    } else {
                        unselect(
                            affordanceId = binding.affordanceId,
                        )
                    }
                }
            }
            .flowOn(backgroundDispatcher)
            .launchIn(scope)

        selectionsManager.selections
            .map { it.values.flatten().toSet() }
            .map { it.contains(binding.affordanceId) }
            .distinctUntilChanged()
            .onEach { isSelected ->
                if (isSet(binding.settingsKey) != isSelected) {
                    set(binding.settingsKey, isSelected)
                }
            }
            .flowOn(backgroundDispatcher)
            .launchIn(scope)
    }

    private fun isSelected(
        affordanceId: String,
    ): Boolean {
        return selectionsManager
            .getSelections() // Map<String, List<String>>
            .values // Collection<List<String>>
            .flatten() // List<String>
            .toSet() // Set<String>
            .contains(affordanceId)
    }

    private fun select(
        slotId: String,
        affordanceId: String,
    ) {
        val affordanceIdsAtSlotId = selectionsManager.getSelections()[slotId] ?: emptyList()
        selectionsManager.setSelections(
            slotId = slotId,
            affordanceIds = affordanceIdsAtSlotId + listOf(affordanceId),
        )
    }

    private fun unselect(
        affordanceId: String,
    ) {
        val currentSelections = selectionsManager.getSelections()
        val slotIdsContainingAffordanceId =
            currentSelections
                .filter { (_, affordanceIds) -> affordanceIds.contains(affordanceId) }
                .map { (slotId, _) -> slotId }

        slotIdsContainingAffordanceId.forEach { slotId ->
            val currentAffordanceIds = currentSelections[slotId] ?: emptyList()
            val affordanceIdsAfterUnselecting =
                currentAffordanceIds.toMutableList().apply { remove(affordanceId) }

            selectionsManager.setSelections(
                slotId = slotId,
                affordanceIds = affordanceIdsAfterUnselecting,
            )
        }
    }

    private fun isSet(
        settingsKey: String,
    ): Boolean {
        return secureSettings.getIntForUser(
            settingsKey,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    private suspend fun set(
        settingsKey: String,
        isSet: Boolean,
    ) {
        withContext(backgroundDispatcher) {
            secureSettings.putInt(
                settingsKey,
                if (isSet) 1 else 0,
            )
        }
    }

    data class Binding(
        val settingsKey: String,
        val slotId: String,
        val affordanceId: String,
    )
}
