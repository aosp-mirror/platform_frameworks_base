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

package com.android.systemui.qs.tiles

import android.app.AlertDialog
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.widget.Switch
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN
import com.android.internal.logging.MetricsLogger
import com.android.systemui.Flags.recordIssueQsTile
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.recordissue.IssueRecordingService
import com.android.systemui.recordissue.IssueRecordingState
import com.android.systemui.recordissue.RecordIssueDialogDelegate
import com.android.systemui.recordissue.TraceurMessageSender
import com.android.systemui.res.R
import com.android.systemui.screenrecord.RecordingService
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject

class RecordIssueTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val keyguardDismissUtil: KeyguardDismissUtil,
    private val keyguardStateController: KeyguardStateController,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val panelInteractor: PanelInteractor,
    private val userContextProvider: UserContextProvider,
    private val traceurMessageSender: TraceurMessageSender,
    @Background private val bgExecutor: Executor,
    private val issueRecordingState: IssueRecordingState,
    private val delegateFactory: RecordIssueDialogDelegate.Factory,
) :
    QSTileImpl<QSTile.BooleanState>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger
    ) {

    private val onRecordingChangeListener = Runnable { refreshState() }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            issueRecordingState.addListener(onRecordingChangeListener)
        } else {
            issueRecordingState.removeListener(onRecordingChangeListener)
        }
    }

    override fun handleDestroy() {
        super.handleDestroy()
        bgExecutor.execute { traceurMessageSender.unbindFromTraceur(mContext) }
    }

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.qs_record_issue_label)

    /**
     * There are SELinux constraints that are stopping this tile from reaching production builds.
     * Once those are resolved, this condition will be removed, but the solution (of properly
     * creating a distince SELinux context for com.android.systemui) is complex and will take time
     * to implement.
     */
    override fun isAvailable(): Boolean = android.os.Build.IS_DEBUGGABLE && recordIssueQsTile()

    override fun newTileState(): QSTile.BooleanState =
        QSTile.BooleanState().apply {
            label = tileLabel
            handlesLongClick = false
        }

    @VisibleForTesting
    public override fun handleClick(expandable: Expandable?) {
        if (issueRecordingState.isRecording) {
            stopIssueRecordingService()
        } else {
            mUiHandler.post { showPrompt(expandable) }
        }
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
                        .dialogTransitionController(DialogCuj(CUJ_SHADE_DIALOG_OPEN, TILE_SPEC))
                        ?.let { dialogTransitionAnimator.show(dialog, it) } ?: dialog.show()
                } else {
                    dialog.show()
                }
                false
            }
        keyguardDismissUtil.executeWhenUnlocked(dismissAction, false, true)
    }

    override fun getLongClickIntent(): Intent? = null

    @VisibleForTesting
    public override fun handleUpdateState(qsTileState: QSTile.BooleanState, arg: Any?) {
        qsTileState.apply {
            if (issueRecordingState.isRecording) {
                value = true
                state = Tile.STATE_ACTIVE
                forceExpandIcon = false
                secondaryLabel = mContext.getString(R.string.qs_record_issue_stop)
                icon = ResourceIcon.get(R.drawable.qs_record_issue_icon_on)
            } else {
                value = false
                state = Tile.STATE_INACTIVE
                forceExpandIcon = true
                secondaryLabel = mContext.getString(R.string.qs_record_issue_start)
                icon = ResourceIcon.get(R.drawable.qs_record_issue_icon_off)
            }
            label = tileLabel
            contentDescription =
                if (TextUtils.isEmpty(secondaryLabel)) label else "$label, $secondaryLabel"
            expandedAccessibilityClassName = Switch::class.java.name
        }
    }

    companion object {
        const val TILE_SPEC = "record_issue"
    }
}
