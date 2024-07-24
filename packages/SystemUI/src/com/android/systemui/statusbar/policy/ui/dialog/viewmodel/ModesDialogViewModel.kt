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

import android.content.Context
import android.content.Intent
import android.provider.Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS
import android.provider.Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.ui.dialog.ModesDialogDelegate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

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
) {
    // Modes that should be displayed in the dialog
    // TODO(b/346519570): Include modes that have not been set up yet.
    private val visibleModes: Flow<List<ZenMode>> =
        zenModeInteractor.modes.map {
            it.filter { mode ->
                mode.rule.isEnabled && (mode.isActive || mode.rule.isManualInvocationAllowed)
            }
        }

    val tiles: Flow<List<ModeTileViewModel>> =
        visibleModes
            .map { modesList ->
                modesList.map { mode ->
                    ModeTileViewModel(
                        id = mode.id,
                        icon = zenModeInteractor.getModeIcon(mode, context),
                        text = mode.rule.name,
                        subtext = getTileSubtext(mode),
                        enabled = mode.isActive,
                        // TODO(b/346519570): This should be some combination of the above, e.g.
                        //  "ON: Do Not Disturb, Until Mon 08:09"; see DndTile.
                        contentDescription = "",
                        onClick = {
                            if (mode.isActive) {
                                zenModeInteractor.deactivateMode(mode)
                            } else {
                                // TODO(b/346519570): Handle duration for DND mode.
                                zenModeInteractor.activateMode(mode)
                            }
                        },
                        onLongClick = {
                            val intent: Intent =
                                Intent(ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
                                    .putExtra(EXTRA_AUTOMATIC_ZEN_RULE_ID, mode.id)
                            dialogDelegate.launchFromDialog(intent)
                        }
                    )
                }
            }
            .flowOn(bgDispatcher)

    private fun getTileSubtext(mode: ZenMode): String {
        // TODO(b/346519570): Use ZenModeConfig.getDescription for manual DND
        val on = context.resources.getString(R.string.zen_mode_on)
        val off = context.resources.getString(R.string.zen_mode_off)
        return mode.rule.triggerDescription ?: if (mode.isActive) on else off
    }
}
