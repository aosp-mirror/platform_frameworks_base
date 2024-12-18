/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.ui.dialog.viewmodel

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.provider.Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS
import android.provider.Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID
import com.android.settingslib.notification.modes.EnableZenModeDialog
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.dialog.QSZenModeDialogMetricsLogger
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.ui.dialog.ModesDialogDelegate
import com.android.systemui.statusbar.policy.ui.dialog.ModesDialogEventLogger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/**
 * Viewmodel for the priority ("zen") modes dialog that can be opened from quick settings. It allows
 * the user to quickly toggle modes.
 */
@SysUISingleton
class ModesDialogViewModel
@Inject
constructor(
    val context: Context,
    zenModeInteractor: ZenModeInteractor,
    @Background val bgDispatcher: CoroutineDispatcher,
    private val dialogDelegate: ModesDialogDelegate,
    private val dialogEventLogger: ModesDialogEventLogger,
) {
    private val zenDialogMetricsLogger = QSZenModeDialogMetricsLogger(context)

    // Modes that should be displayed in the dialog
    private val visibleModes: Flow<List<ZenMode>> =
        zenModeInteractor.modes
            // While this is being collected (or in other words, while the dialog is open), we don't
            // want a mode to disappear from the list if, for instance, the user deactivates it,
            // since that can be confusing (similar to how we have visual stability for
            // notifications while the shade is open).
            // This ensures new modes are added to the list, and updates to modes already in the
            // list are registered correctly.
            .scan(listOf()) { prev, modes ->
                val prevIds = prev.map { it.id }.toSet()

                modes.filter { mode ->
                    when {
                        // Mode appeared previously -> keep it even if otherwise we may have
                        // filtered it
                        mode.id in prevIds -> true
                        // Mode is enabled -> show if active (so user can toggle off), or if it
                        // can be manually toggled on
                        mode.rule.isEnabled -> mode.isActive || mode.rule.isManualInvocationAllowed
                        // Mode was created as disabled, or disabled by the app that owns it ->
                        // will be shown with a "Set up" text
                        !mode.rule.isEnabled -> mode.status == ZenMode.Status.DISABLED_BY_OTHER
                        else -> false
                    }
                }
            }

    val tiles: Flow<List<ModeTileViewModel>> =
        visibleModes
            .map { modesList ->
                modesList.map { mode ->
                    ModeTileViewModel(
                        id = mode.id,
                        icon = zenModeInteractor.getModeIcon(mode).drawable().asIcon(),
                        text = mode.name,
                        subtext = getTileSubtext(mode),
                        subtextDescription = getModeDescription(mode) ?: "",
                        enabled = mode.isActive,
                        stateDescription =
                            context.getString(
                                if (mode.isActive) R.string.zen_mode_on else R.string.zen_mode_off
                            ),
                        onClick = {
                            if (!mode.rule.isEnabled) {
                                openSettings(mode)
                            } else if (mode.isActive) {
                                dialogEventLogger.logModeOff(mode)
                                zenModeInteractor.deactivateMode(mode)
                            } else {
                                if (mode.rule.isManualInvocationAllowed) {
                                    if (zenModeInteractor.shouldAskForZenDuration(mode)) {
                                        dialogEventLogger.logOpenDurationDialog(mode)
                                        // NOTE: The dialog handles turning on the mode itself.
                                        val dialog = makeZenModeDialog()
                                        dialog.show()
                                    } else {
                                        dialogEventLogger.logModeOn(mode)
                                        zenModeInteractor.activateMode(mode)
                                    }
                                }
                            }
                        },
                        onLongClick = { openSettings(mode) },
                        onLongClickLabel =
                            context.resources.getString(R.string.accessibility_long_click_tile)
                    )
                }
            }
            .flowOn(bgDispatcher)

    private fun openSettings(mode: ZenMode) {
        dialogEventLogger.logModeSettings(mode)
        val intent: Intent =
            Intent(ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
                .putExtra(EXTRA_AUTOMATIC_ZEN_RULE_ID, mode.id)

        dialogDelegate.launchFromDialog(intent)
    }

    /**
     * Returns a description of the mode, which is:
     *   * a prompt to set up the mode if it is not enabled
     *   * if it cannot be manually activated, text that says so
     *   * otherwise, the trigger description of the mode if it exists...
     *   * ...or null if it doesn't
     *
     * This description is used directly for the content description of a mode tile for screen
     * readers, and for the tile subtext will be augmented with the current status of the mode.
     */
    private fun getModeDescription(mode: ZenMode): String? {
        if (!mode.rule.isEnabled) {
            return context.resources.getString(R.string.zen_mode_set_up)
        }
        if (!mode.rule.isManualInvocationAllowed && !mode.isActive) {
            return context.resources.getString(R.string.zen_mode_no_manual_invocation)
        }
        return mode.getDynamicDescription(context)
    }

    private fun getTileSubtext(mode: ZenMode): String {
        val modeDescription = getModeDescription(mode)
        return if (mode.isActive) {
            if (modeDescription != null) {
                context.getString(R.string.zen_mode_on_with_details, modeDescription)
            } else {
                context.getString(R.string.zen_mode_on)
            }
        } else {
            modeDescription ?: context.getString(R.string.zen_mode_off)
        }
    }

    private fun makeZenModeDialog(): Dialog {
        val dialog =
            EnableZenModeDialog(
                    context,
                    R.style.Theme_SystemUI_Dialog,
                    /* cancelIsNeutral= */ true,
                    zenDialogMetricsLogger
                )
                .createDialog()
        SystemUIDialog.applyFlags(dialog)
        SystemUIDialog.setShowForAllUsers(dialog, true)
        SystemUIDialog.registerDismissListener(dialog)
        SystemUIDialog.setDialogSize(dialog)
        return dialog
    }
}
