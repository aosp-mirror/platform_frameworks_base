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
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.app.tracing.coroutines.withContext
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.logging.KeyguardQuickAffordancesLogger
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.devicepolicy.areKeyguardShortcutsDisabled
import com.android.systemui.dock.DockManager
import com.android.systemui.dock.retrieveIsDocked
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardPickerFlag
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation
import com.android.systemui.keyguard.shared.model.KeyguardSlotPickerRepresentation
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.customization.data.content.CustomizationProviderContract as Contract
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEYGUARD_QUICK_AFFORDANCE_ID_NONE
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardQuickAffordanceInteractor
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val lockPatternUtils: LockPatternUtils,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val featureFlags: FeatureFlags,
    private val repository: Lazy<KeyguardQuickAffordanceRepository>,
    private val launchAnimator: DialogTransitionAnimator,
    private val logger: KeyguardQuickAffordancesLogger,
    private val metricsLogger: KeyguardQuickAffordancesMetricsLogger,
    private val devicePolicyManager: DevicePolicyManager,
    private val dockManager: DockManager,
    private val biometricSettingsRepository: BiometricSettingsRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Application private val appContext: Context,
    private val sceneInteractor: Lazy<SceneInteractor>,
) {

    /**
     * Whether the UI should use the long press gesture to activate quick affordances.
     *
     * If `false`, the UI goes back to using single taps.
     */
    fun useLongPress(): Flow<Boolean> = dockManager.retrieveIsDocked().map { !it }

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
            if (SceneContainerFlag.isEnabled) {
                sceneInteractor
                    .get()
                    .transitionState
                    .map {
                        when (it) {
                            is ObservableTransitionState.Idle ->
                                it.currentScene == Scenes.Lockscreen
                            is ObservableTransitionState.Transition ->
                                it.fromContent == Scenes.Lockscreen ||
                                    it.toContent == Scenes.Lockscreen
                        }
                    }
                    .distinctUntilChanged()
            } else {
                keyguardInteractor.isKeyguardShowing
            },
            shadeInteractor.anyExpansion.map { it < 1.0f }.distinctUntilChanged(),
            biometricSettingsRepository.isCurrentUserInLockdown,
        ) { affordance, isDozing, isKeyguardShowing, isQuickSettingsVisible, isUserInLockdown ->
            if (!isDozing && isKeyguardShowing && isQuickSettingsVisible && !isUserInLockdown) {
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
     *
     * @param overrideQuickAffordanceId If null, return the currently-set quick affordance;
     *   otherwise, override and return the correspondent [KeyguardQuickAffordanceModel].
     */
    suspend fun quickAffordanceAlwaysVisible(
        position: KeyguardQuickAffordancePosition,
        overrideQuickAffordanceId: String? = null,
    ): Flow<KeyguardQuickAffordanceModel> {
        return if (isFeatureDisabledByDevicePolicy()) {
            flowOf(KeyguardQuickAffordanceModel.Hidden)
        } else {
            quickAffordanceInternal(position, overrideQuickAffordanceId)
        }
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
        val (decodedSlotId, decodedConfigKey) = configKey.decode()
        val config =
            repository.get().selections.value[decodedSlotId]?.find { it.key == decodedConfigKey }
        if (config == null) {
            Log.e(TAG, "Affordance config with key of \"$configKey\" not found!")
            return
        }
        logger.logQuickAffordanceTriggered(decodedSlotId, decodedConfigKey)
        metricsLogger.logOnShortcutTriggered(slotId, configKey)

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

        logger.logQuickAffordanceSelected(slotId, affordanceId)
        metricsLogger.logOnShortcutSelected(slotId, affordanceId)
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
        position: KeyguardQuickAffordancePosition,
        overrideAffordanceId: String? = null,
    ): Flow<KeyguardQuickAffordanceModel> =
        repository
            .get()
            .selections
            .map { selections ->
                val overrideQuickAffordanceConfigs =
                    overrideAffordanceId?.let {
                        if (it == KEYGUARD_QUICK_AFFORDANCE_ID_NONE) {
                            emptyList()
                        } else {
                            val config = repository.get().getConfig(it)
                            listOfNotNull(config)
                        }
                    }
                overrideQuickAffordanceConfigs ?: selections[position.toSlotId()] ?: emptyList()
            }
            .flatMapLatest { configs -> combinedConfigs(position, configs) }

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
                    configKey = configKey.encode(position.toSlotId()),
                    icon = visibleState.icon,
                    activationState = visibleState.activationState,
                )
            } else {
                KeyguardQuickAffordanceModel.Hidden
            }
        }
    }

    private fun showDialog(dialog: AlertDialog, expandable: Expandable?) {
        expandable?.dialogTransitionController()?.let { controller ->
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
                expandable?.activityTransitionController(),
            )
        } else {
            activityStarter.startActivity(
                intent,
                true /* dismissShade */,
                expandable?.activityTransitionController(),
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
        if (isFeatureDisabledByDevicePolicy()) {
            return emptyList()
        }

        return repository.get().getSlotPickerRepresentations()
    }

    suspend fun getPickerFlags(): List<KeyguardPickerFlag> {
        return listOf(
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_CUSTOM_LOCK_SCREEN_QUICK_AFFORDANCES_ENABLED,
                value =
                    !isFeatureDisabledByDevicePolicy() &&
                        appContext.resources.getBoolean(R.bool.custom_lockscreen_shortcuts_enabled),
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
            ),
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_PAGE_TRANSITIONS,
                value = featureFlags.isEnabled(Flags.WALLPAPER_PICKER_PAGE_TRANSITIONS)
            ),
            KeyguardPickerFlag(
                name = Contract.FlagsTable.FLAG_NAME_WALLPAPER_PICKER_PREVIEW_ANIMATION,
                value = featureFlags.isEnabled(Flags.WALLPAPER_PICKER_PREVIEW_ANIMATION)
            ),
        )
    }

    private suspend fun isFeatureDisabledByDevicePolicy(): Boolean =
        withContext("$TAG#isFeatureDisabledByDevicePolicy", backgroundDispatcher) {
            devicePolicyManager.areKeyguardShortcutsDisabled(userId = userTracker.userId)
        }

    companion object {
        private const val TAG = "KeyguardQuickAffordanceInteractor"
        private const val DELIMITER = "::"
    }
}
