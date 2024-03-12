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
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.view.View
import android.widget.Switch
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN
import com.android.internal.logging.MetricsLogger
import com.android.systemui.Flags.recordIssueQsTile
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.recordissue.RecordIssueDialogDelegate
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
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
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val sysuiDialogFactory: SystemUIDialog.Factory,
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

    @VisibleForTesting var isRecording: Boolean = false

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.qs_record_issue_label)

    override fun isAvailable(): Boolean = recordIssueQsTile()

    override fun newTileState(): QSTile.BooleanState =
        QSTile.BooleanState().apply {
            label = tileLabel
            handlesLongClick = false
        }

    @VisibleForTesting
    public override fun handleClick(view: View?) {
        if (isRecording) {
            isRecording = false
        } else {
            mUiHandler.post { showPrompt(view) }
        }
        refreshState()
    }

    private fun showPrompt(view: View?) {
        val dialog: AlertDialog =
            RecordIssueDialogDelegate(sysuiDialogFactory) {
                    isRecording = true
                    refreshState()
                }
                .createDialog()
        val dismissAction =
            ActivityStarter.OnDismissAction {
                // We animate from the touched view only if we are not on the keyguard, given
                // that if we are we will dismiss it which will also collapse the shade.
                if (view != null && !keyguardStateController.isShowing) {
                    dialogLaunchAnimator.showFromView(
                        dialog,
                        view,
                        DialogCuj(CUJ_SHADE_DIALOG_OPEN, TILE_SPEC)
                    )
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
            if (isRecording) {
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
