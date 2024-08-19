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

package com.android.systemui.qs.tiles.impl.irecording

import android.app.AlertDialog
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.util.Log
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.recordissue.IssueRecordingService
import com.android.systemui.recordissue.RecordIssueDialogDelegate
import com.android.systemui.recordissue.RecordIssueModule.Companion.TILE_SPEC
import com.android.systemui.screenrecord.RecordingService
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

private const val TAG = "IssueRecordingActionInteractor"

class IssueRecordingUserActionInteractor
@Inject
constructor(
    @Main private val mainCoroutineContext: CoroutineContext,
    private val keyguardDismissUtil: KeyguardDismissUtil,
    private val keyguardStateController: KeyguardStateController,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val panelInteractor: PanelInteractor,
    private val userContextProvider: UserContextProvider,
    private val delegateFactory: RecordIssueDialogDelegate.Factory,
) : QSTileUserActionInteractor<IssueRecordingModel> {

    override suspend fun handleInput(input: QSTileInput<IssueRecordingModel>) {
        if (input.action is QSTileUserAction.Click) {
            if (input.data.isRecording) {
                stopIssueRecordingService()
            } else {
                withContext(mainCoroutineContext) { showPrompt(input.action.expandable) }
            }
        } else {
            Log.v(TAG, "the RecordIssueTile doesn't handle ${input.action} events yet.")
        }
    }

    private fun showPrompt(expandable: Expandable?) {
        val dialog: AlertDialog =
            delegateFactory
                .create {
                    startIssueRecordingService()
                    dialogTransitionAnimator.disableAllCurrentDialogsExitAnimations()
                    panelInteractor.collapsePanels()
                }
                .createDialog()
        val dismissAction =
            ActivityStarter.OnDismissAction {
                // We animate from the touched view only if we are not on the keyguard, given
                // that if we are we will dismiss it which will also collapse the shade.
                if (expandable != null && !keyguardStateController.isShowing) {
                    expandable
                        .dialogTransitionController(
                            DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, TILE_SPEC)
                        )
                        ?.let { dialogTransitionAnimator.show(dialog, it) } ?: dialog.show()
                } else {
                    dialog.show()
                }
                false
            }
        keyguardDismissUtil.executeWhenUnlocked(dismissAction, false, true)
    }

    private fun startIssueRecordingService() =
        PendingIntent.getForegroundService(
                userContextProvider.userContext,
                RecordingService.REQUEST_CODE,
                IssueRecordingService.getStartIntent(userContextProvider.userContext),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            .send(BroadcastOptions.makeBasic().apply { isInteractive = true }.toBundle())

    private fun stopIssueRecordingService() =
        PendingIntent.getService(
                userContextProvider.userContext,
                RecordingService.REQUEST_CODE,
                IssueRecordingService.getStopIntent(userContextProvider.userContext),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            .send(BroadcastOptions.makeBasic().apply { isInteractive = true }.toBundle())
}
