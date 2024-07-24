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

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

//noinspection CleanArchitectureDependencyViolation: dialog needs to be opened on click
import android.content.Intent
import android.provider.Settings
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.policy.ui.dialog.ModesDialogDelegate
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

class ModesTileUserActionInteractor
@Inject
constructor(
    @Main private val coroutineContext: CoroutineContext,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegate: ModesDialogDelegate,
) : QSTileUserActionInteractor<ModesTileModel> {
    val longClickIntent = Intent(Settings.ACTION_ZEN_MODE_SETTINGS)

    override suspend fun handleInput(input: QSTileInput<ModesTileModel>) {
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    handleClick(action.expandable)
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(action.expandable, longClickIntent)
                }
            }
        }
    }

    suspend fun handleClick(expandable: Expandable?) {
        // Show a dialog with the list of modes to configure. Dialogs shown by the
        // DialogTransitionAnimator must be created and shown on the main thread, so we post it to
        // the UI handler.
        withContext(coroutineContext) {
            val dialog = dialogDelegate.createDialog()

            expandable
                ?.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
                )
                ?.let { controller -> dialogTransitionAnimator.show(dialog, controller) }
                ?: dialog.show()
        }
    }

    companion object {
        private const val INTERACTION_JANK_TAG = "configure_priority_modes"
    }
}
