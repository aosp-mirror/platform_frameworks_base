/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.util.Log
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.devicepolicy.areKeyguardShortcutsDisabled
import com.android.systemui.dock.DockManager
import com.android.systemui.dock.retrieveIsDocked
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceRegistry
import com.android.systemui.keyguard.shared.model.KeyguardPickerFlag
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation
import com.android.systemui.keyguard.shared.model.KeyguardSlotPickerRepresentation
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.customization.data.content.CustomizationProviderContract as Contract
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardQuickAffordanceInteractor
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val registry: KeyguardQuickAffordanceRegistry<out KeyguardQuickAffordanceConfig>,
    private val lockPatternUtils: LockPatternUtils,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val featureFlags: FeatureFlags,
    private val repository: Lazy<KeyguardQuickAffordanceRepository>,
    private val launchAnimator: DialogLaunchAnimator,
    private val logger: KeyguardQuickAffordancesMetricsLogger,
    private val devicePolicyManager: DevicePolicyManager,
    private val dockManager: DockManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    private val isUsingRepository: Boolean
        get() = featureFlags.isEnabled(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES)

    /**
     * Whether the UI should use the long press gesture to activate quick affordances.
     *
     * If `false`, the UI goes back to using single taps.
     */
    fun useLongPress(): Flow<Boolean> =
        if (featureFlags.isEnabled(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES)) {
            dockManager.retrieveIsDocked().map { !it }
        } else {
            flowOf(false)
        }

    /** Returns an observable for the quick affordance at the given position. */
    suspend fun quickAffordance(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceModel> {
        if (isFeatureDisabledByDevicePolicy()) {
            return flowOf(KeyguardQuickAffordanceModel.Hidden)
        }

        return combine(
            quickAffordanceAlwaysVisible(position),
            keyguardInteractor.isDozing,
            keyguardInteractor.isKeyguardShowing,
            keyguardInteractor.isQuickSettingsVisible
        ) { affordance, isDozing, isKeyguardShowing, isQuickSettingsVisible ->
            if (!isDozing && isKeyguardShowing && !isQuickSettingsVisible) {
                affordance
            } else {
                KeyguardQuickAffordanceModel.Hidden
            }
        }
    }

    /**
     * Returns an observable for the quick affordance at the given position but always visible,
     * regardless of lock screen state.
     *
     * This is useful for experiences like the lock screen preview mode, where the affordances must
     * always be visible.
     */
    fun quickAffordanceAlwaysVisible(
        position: KeyguardQuickAffordancePosition,
    ): Flow<KeyguardQuickAffordanceModel> {
        return quickAffordanceInternal(position)
    }

    /**
     * Notifies that a quick affordance has been "triggered" (clicked) by the user.
     *
     * @param configKey The configuration key corresponding to the [KeyguardQuickAffordanceModel] of
     *   the affordance that was clicked
     * @param expandable An optional [Expandable] for the activity- or dialog-launch animation
     * @param slotId The id of the lockscreen slot that the affordance is in
     */
    fun onQuickAffordanceTriggered(
        configKey: String,
        expandable: Expandable?,
        slotId: String,
    ) {
        @Suppress("UNCHECKED_CAST")
        val config =
            if (isUsingRepository) {
                val (slotId, decodedConfigKey) = configKey.decode()
                repository.get().selections.value[slotId]?.find { it.key == decodedConfigKey }
            } else {
                registry.get(configKey)
            }
        if (config == null) {
            Log.e(TAG, "Affordance config with key of \"$configKey\" not found!")
            return
        }
        logger.logOnShortcutTriggered(slotId, configKey)

        when (val result = config.onTriggered(expandable)) {
            is KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity ->
                launchQuickAffordance(
                    intent = result.intent,
                    canShowWhileLocked = result.canShowWhileLocked,
                    expandable = expandable,
                )
            is KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled -> Unit
            is KeyguardQuickAffordanceConfig.OnTriggeredResult.ShowDialog ->
                showDialog(
                    result.dialog,
                    result.expandable,
                )
        }
    }

    /**
     * Selects an affordance with the given ID on the slot with the given ID.
     *
     * @return `true` if the affordance was selected successfully; `false` otherwise.
     */
    suspend fun select(slotId: String, affordanceId: String): Boolean {
        check(isUsingRepository)
        if (isFeatureDisabledByDevicePolicy()) {
            return false
        }

        val slots = repository.get().getSlotPickerRepresentations()
        val slot = slots.find { it.id == slotId } ?: return false
        val selections =
            repository
                .get()
                .getCurrentSelections()
                .getOrDefault(slotId, emptyList())
                .toMutableList()
        val alreadySelected = selections.remove(affordanceId)
        if (!alreadySelected) {
            while (selections.size > 0 && selections.size >= slot.maxSelectedAffordances) {
                selections.removeAt(0)
            }
        }

        selections.add(affordanceId)

        repository
            .get()
            .setSelections(
                slotId = slotId,
                affordanceIds = selections,
            )

        logger.logOnShortcutSelected(slotId, affordanceId)
        return true
    }

    /**
     * Unselects one or all affordances from the slot with the given ID.
     *
     * @param slotId The ID of the slot.
     * @param affordanceId The ID of the affordance to remove; if `null`, removes all affordances
     *   from the slot.
     * @return `true` if the affordance was successfully removed; `false` otherwise (for example, if
     *   the affordance was not on the slot to begin with).
     */
    suspend fun unselect(slotId: String, affordanceId: String?): Boolean {
        check(isUsingRepository)
        if (isFeatureDisabledByDevicePolicy()) {
            return false
        }

        val slots = repository.get().getSlotPickerRepresentations()
        if (slots.find { it.id == slotId } == null) {
            return false
        }

        if (affordanceId.isNullOrEmpty()) {
            return if (
                repository.get().getCurrentSelections().getOrDefault(slotId, emptyList()).isEmpty()
            ) {
                false
            } else {
                repository.get().setSelections(slotId = slotId, affordanceIds = emptyList())
                true
            }
        }

        val selections =
            repository
                .get()
                .getCurrentSelections()
                .getOrDefault(slotId, emptyList())
                .toMutableList()
        return if (selections.remove(affordanceId)) {
            repository
                .get()
                .setSelections(
                    slotId = slotId,
                    affordanceIds = selections,
                )
            true
        } else {
            false
        }
    }

    /** Returns affordance IDs indexed by slot ID, for all known slots. */
    suspend fun getSelections(): Map<String, List<KeyguardQuickAffordancePickerRepresentation>> {
        if (isFeatureDisabledByDevicePolicy()) {
            return emptyMap()
        }

        val slots = repository.get().getSlotPickerRepresentations()
        val selections = repository.get().getCurrentSelections()
        val affordanceById =
            getAffordancePickerRepresentations().associateBy { affordance -> affordance.id }
        return slots.associate { slot ->
            slot.id to
                (selections[slot.id] ?: emptyList()).mapNotNull { affordanceId ->
                    affordanceById[affordanceId]
                }
        }
    }

    private fun quickAffordanceInternal(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceModel> {
        return if (isUsingRepository) {
            repository
                .get()
                .selections
                .map { it[position.toSlotId()] ?: emptyList() }
                .flatMapLatest { configs -> combinedConfigs(position, configs) }
        } else {
            combinedConfigs(position, registry.getAll(position))
        }
    }

    private fun combinedConfigs(
        position: KeyguardQuickAffordancePosition,
        configs: List<KeyguardQuickAffordanceConfig>,
    ): Flow<KeyguardQuickAffordanceModel> {
        if (configs.isEmpty()) {
            return flowOf(KeyguardQuickAffordanceModel.Hidden)
        }

        return combine(
            configs.map { config ->
                // We emit an initial "Hidden" value to make sure that there's always an
                // initial value and avoid subtle bugs where the downstream isn't receiving
                // any values because one config implementation is not emitting an initial
                // value. For example, see b/244296596.
                config.lockScreenState.onStart {
                    emit(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
                }
            }
        ) { states ->
            val index =
                states.indexOfFirst { state ->
                    state is KeyguardQuickAffordanceConfig.LockScreenState.Visible
                }
            if (index != -1) {
                val visibleState =
                    states[index] as KeyguardQuickAffordanceConfig.LockScreenState.Visible
                val configKey = configs[index].key
                KeyguardQuickAffordanceModel.Visible(
                    configKey =
                        if (isUsingRepository) {
                            configKey.encode(position.toSlotId())
                        } else {
                            configKey
                        },
                    icon = visibleState.icon,
                    activationState = visibleState.activationState,
                )
            } else {
                KeyguardQuickAffordanceModel.Hidden
            }
        }
    }

    private fun showDialog(dialog: AlertDialog, expandable: Expandable?) {
        expandable?.dialogLaunchController()?.let { controller ->
            SystemUIDialog.applyFlags(dialog)
            SystemUIDialog.setShowForAllUsers(dialog, true)
            SystemUIDialog.registerDismissListener(dialog)
            SystemUIDialog.setDialogSize(dialog)
            launchAnimator.show(dialog, controller)
        }
    }

    private fun launchQuickAffordance(
        intent: Intent,
        canShowWhileLocked: Boolean,
        expandable: Expandable?,
    ) {
        @LockPatternUtils.StrongAuthTracker.StrongAuthFlags
        val strongAuthFlags =
            lockPatternUtils.getStrongAuthForUser(userTracker.userHandle.identifier)
        val needsToUnlockFirst =
            when {
                strongAuthFlags ==
                    LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT -> true
                !canShowWhileLocked && !keyguardStateController.isUnlocked -> true
                else -> false
            }
        if (needsToUnlockFirst) {
            activityStarter.postStartActivityDismissingKeyguard(
                intent,
                0 /* delay */,
                expandable?.activityLaunchController(),
            )
        } else {
            activityStarter.startActivity(
                intent,
                true /* dismissShade */,
                expandable?.activityLaunchController(),
                true /* showOverLockscreenWhenLocked */,
            )
        }
    }

    private fun String.encode(slotId: String): String {
        return "$slotId$DELIMITER$this"
    }

    private fun String.decode(): Pair<String, String> {
        val splitUp = this.split(DELIMITER)
        return Pair(splitUp[0], splitUp[1])
    }

    suspend fun getAffordancePickerRepresentations():
        List<KeyguardQuickAffordancePickerRepresentation> {
        return repository.get().getAffordancePickerRepresentations()
    }

    suspend fun getSlotPickerRepresentations(): List<KeyguardSlotPickerRepresentation> {
        check(isUsingRepository)

        if (isFeatureDisabledByDevicePolicy()) {
            return emptyList()
        }

        return repository.get().getSlotPickerRepresentations()
    }

    suspend fun getPickerFlags(): List<KeyguardPickerFlag> {
        return listOf(
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_REVAMPED_WALLPAPER_UI,
                value = featureFlags.isEnabled(Flags.REVAMPED_WALLPAPER_UI),
            ),
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_CUSTOM_LOCK_SCREEN_QUICK_AFFORDANCES_ENABLED,
                value =
                    !isFeatureDisabledByDevicePolicy() &&
                        featureFlags.isEnabled(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES),
            ),
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_CUSTOM_CLOCKS_ENABLED,
                value = featureFlags.isEnabled(Flags.LOCKSCREEN_CUSTOM_CLOCKS),
            ),
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_WALLPAPER_FULLSCREEN_PREVIEW,
                value = featureFlags.isEnabled(Flags.WALLPAPER_FULLSCREEN_PREVIEW),
            ),
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_MONOCHROMATIC_THEME,
                value = featureFlags.isEnabled(Flags.MONOCHROMATIC_THEME)
            ),
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_WALLPAPER_PICKER_UI_FOR_AIWP,
                value = featureFlags.isEnabled(Flags.WALLPAPER_PICKER_UI_FOR_AIWP)
            )
        )
    }

    private suspend fun isFeatureDisabledByDevicePolicy(): Boolean =
        withContext(backgroundDispatcher) {
            devicePolicyManager.areKeyguardShortcutsDisabled(userId = userTracker.userId)
        }

    companion object {
        private const val TAG = "KeyguardQuickAffordanceInteractor"
        private const val DELIMITER = "::"
    }
}
