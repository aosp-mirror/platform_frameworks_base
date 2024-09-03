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

package com.android.systemui.qs.tiles.impl.saver.domain.interactor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.saver.domain.DataSaverDialogDelegate
import com.android.systemui.qs.tiles.impl.saver.domain.model.DataSaverTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.settings.UserFileManager
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.DataSaverController
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Handles data saver tile clicks. */
class DataSaverTileUserActionInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Main private val coroutineContext: CoroutineContext,
    @Background private val backgroundContext: CoroutineContext,
    private val dataSaverController: DataSaverController,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    userFileManager: UserFileManager,
) : QSTileUserActionInteractor<DataSaverTileModel> {
    companion object {
        private const val INTERACTION_JANK_TAG = "start_data_saver"
        const val PREFS = "data_saver"
        const val DIALOG_SHOWN = "data_saver_dialog_shown"
    }

    val sharedPreferences =
        userFileManager.getSharedPreferences(PREFS, Context.MODE_PRIVATE, context.userId)

    override suspend fun handleInput(input: QSTileInput<DataSaverTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    val wasEnabled: Boolean = data.isEnabled
                    if (wasEnabled || sharedPreferences.getBoolean(DIALOG_SHOWN, false)) {
                        withContext(backgroundContext) {
                            dataSaverController.setDataSaverEnabled(!wasEnabled)
                        }
                        return@with
                    }
                    // Show a dialog to confirm first. Dialogs shown by the DialogTransitionAnimator
                    // must be created and shown on the main thread, so we post it to the UI
                    // handler
                    withContext(coroutineContext) {
                        val dialogDelegate =
                            DataSaverDialogDelegate(
                                systemUIDialogFactory,
                                context,
                                backgroundContext,
                                dataSaverController,
                                sharedPreferences
                            )
                        val dialog = systemUIDialogFactory.create(dialogDelegate, context)

                        action.expandable
                            ?.dialogTransitionController(
                                DialogCuj(
                                    InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                    INTERACTION_JANK_TAG
                                )
                            )
                            ?.let { controller ->
                                dialogTransitionAnimator.show(dialog, controller)
                            } ?: dialog.show()
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_DATA_SAVER_SETTINGS)
                    )
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }
}
