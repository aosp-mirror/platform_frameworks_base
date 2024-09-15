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

package com.android.systemui.qs.tiles.impl.screenrecord.domain.interactor

import android.content.Context
import android.util.Log
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepository
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Handles screen recorder tile clicks. */
class ScreenRecordTileUserActionInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainContext: CoroutineContext,
    @Background private val backgroundContext: CoroutineContext,
    private val screenRecordRepository: ScreenRecordRepository,
    private val recordingController: RecordingController,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardDismissUtil: KeyguardDismissUtil,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val panelInteractor: PanelInteractor,
    private val mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    private val featureFlags: FeatureFlagsClassic,
    private val activityStarter: ActivityStarter,
) : QSTileUserActionInteractor<ScreenRecordModel> {
    override suspend fun handleInput(input: QSTileInput<ScreenRecordModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    when (data) {
                        is ScreenRecordModel.Starting -> {
                            Log.d(TAG, "Cancelling countdown")
                            withContext(backgroundContext) { recordingController.cancelCountdown() }
                        }
                        is ScreenRecordModel.Recording -> screenRecordRepository.stopRecording()
                        is ScreenRecordModel.DoingNothing ->
                            withContext(mainContext) {
                                showPrompt(action.expandable, user.identifier)
                            }
                    }
                }
                is QSTileUserAction.LongClick -> {} // no-op
            }
        }

    private fun showPrompt(expandable: Expandable?, userId: Int) {
        // Create the recording dialog that will collapse the shade only if we start the recording.
        val onStartRecordingClicked = Runnable {
            // We dismiss the shade. Since starting the recording will also dismiss the dialog, we
            // disable the exit animation which looks weird when it happens at the same time as the
            // shade collapsing.
            dialogTransitionAnimator.disableAllCurrentDialogsExitAnimations()
            panelInteractor.collapsePanels()
        }

        val dialog =
            recordingController.createScreenRecordDialog(
                context,
                featureFlags,
                dialogTransitionAnimator,
                activityStarter,
                onStartRecordingClicked
            )

        if (dialog == null) {
            Log.w(TAG, "showPrompt: dialog was null")
            return
        }

        // We animate from the touched expandable only if we are not on the keyguard, given that if
        // we
        // are we will dismiss it which will also collapse the shade.
        val shouldAnimateFromExpandable =
            expandable != null && !keyguardInteractor.isKeyguardShowing()
        val dismissAction =
            ActivityStarter.OnDismissAction {
                if (shouldAnimateFromExpandable) {
                    val controller =
                        expandable?.dialogTransitionController(
                            DialogCuj(
                                InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                INTERACTION_JANK_TAG
                            )
                        )
                    controller?.let {
                        dialogTransitionAnimator.show(
                            dialog,
                            controller,
                            animateBackgroundBoundsChange = true,
                        )
                    } ?: dialog.show()
                } else {
                    dialog.show()
                }
                mediaProjectionMetricsLogger.notifyPermissionRequestDisplayed(userId)
                false
            }

        keyguardDismissUtil.executeWhenUnlocked(
            dismissAction,
            false /* requiresShadeOpen */,
            true /* afterKeyguardDone */
        )
    }

    private companion object {
        const val TAG = "ScreenRecordTileUserActionInteractor"
        const val INTERACTION_JANK_TAG = "screen_record"
    }
}
