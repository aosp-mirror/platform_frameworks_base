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

package com.android.systemui.recordissue

import android.app.IActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.qualifiers.LongRunning
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.res.R
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.RecordingService
import com.android.systemui.screenrecord.RecordingServiceStrings
import com.android.systemui.screenrecord.ScreenMediaRecorder.SavedRecording
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.traceur.MessageConstants.INTENT_EXTRA_TRACE_TYPE
import com.android.traceur.PresetTraceConfigs
import com.android.traceur.TraceConfig
import java.util.concurrent.Executor
import javax.inject.Inject

class IssueRecordingService
@Inject
constructor(
    controller: RecordingController,
    @LongRunning private val bgExecutor: Executor,
    @Main handler: Handler,
    uiEventLogger: UiEventLogger,
    notificationManager: NotificationManager,
    userContextProvider: UserContextProvider,
    keyguardDismissUtil: KeyguardDismissUtil,
    dialogTransitionAnimator: DialogTransitionAnimator,
    panelInteractor: PanelInteractor,
    private val issueRecordingState: IssueRecordingState,
    traceurConnectionProvider: TraceurConnection.Provider,
    iActivityManager: IActivityManager,
    screenRecordingStartTimeStore: ScreenRecordingStartTimeStore,
) :
    RecordingService(
        controller,
        bgExecutor,
        handler,
        uiEventLogger,
        notificationManager,
        userContextProvider,
        keyguardDismissUtil,
        screenRecordingStartTimeStore,
    ) {

    private val traceurConnection: TraceurConnection = traceurConnectionProvider.create()

    private val session =
        IssueRecordingServiceSession(
            bgExecutor,
            dialogTransitionAnimator,
            panelInteractor,
            traceurConnection,
            issueRecordingState,
            iActivityManager,
            notificationManager,
            userContextProvider,
            screenRecordingStartTimeStore,
        )

    /**
     * It is necessary to bind to IssueRecordingService from the Record Issue Tile because there are
     * instances where this service is not created in the same user profile as the record issue tile
     * aka, headless system user mode. In those instances, the TraceurConnection will be considered
     * a leak in between notification actions unless the tile is bound to this service to keep it
     * alive.
     */
    override fun onBind(intent: Intent): IBinder? {
        traceurConnection.doBind()
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        traceurConnection.doUnBind()
        return super.onUnbind(intent)
    }

    override fun getTag(): String = TAG

    override fun getChannelId(): String = CHANNEL_ID

    override fun provideRecordingServiceStrings(): RecordingServiceStrings = IrsStrings(resources)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(getTag(), "handling action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                val screenRecord = intent.getBooleanExtra(EXTRA_SCREEN_RECORD, false)
                with(session) {
                    traceConfig =
                        intent.getParcelableExtra(INTENT_EXTRA_TRACE_TYPE, TraceConfig::class.java)
                            ?: PresetTraceConfigs.getDefaultConfig()
                    takeBugReport = intent.getBooleanExtra(EXTRA_BUG_REPORT, false)
                    this.screenRecord = screenRecord
                    start()
                }
                if (!screenRecord) {
                    // If we don't want to record the screen, the ACTION_SHOW_START_NOTIF action
                    // will circumvent the RecordingService's screen recording start code.
                    return super.onStartCommand(Intent(ACTION_SHOW_START_NOTIF), flags, startId)
                }
            }
            ACTION_STOP,
            ACTION_STOP_NOTIF -> session.stop()
            ACTION_SHARE -> {
                session.share(
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, mNotificationId),
                    intent.getParcelableExtra(EXTRA_PATH, Uri::class.java),
                )
                // Unlike all other actions, action_share has different behavior for the screen
                // recording qs tile than it does for the record issue qs tile. Return sticky to
                // avoid running any of the base class' code for this action.
                return START_STICKY
            }
            else -> {}
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * If the user chooses to create a bugreport, we do not want to make them click share twice. To
     * avoid that, the code immediately triggers the bugreport flow which will handle the rest.
     */
    override fun onRecordingSaved(recording: SavedRecording?, currentUser: UserHandle) {
        if (session.takeBugReport) {
            session.share(mNotificationId, recording?.uri)
        } else {
            super.onRecordingSaved(recording, currentUser)
        }
    }

    companion object {
        private const val TAG = "IssueRecordingService"
        private const val CHANNEL_ID = "issue_record"
        const val EXTRA_SCREEN_RECORD = "extra_screenRecord"
        const val EXTRA_BUG_REPORT = "extra_bugReport"

        /**
         * Get an intent to stop the issue recording service.
         *
         * @param context Context from the requesting activity
         * @return
         */
        fun getStopIntent(context: Context): Intent =
            Intent(context, IssueRecordingService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(Intent.EXTRA_USER_HANDLE, context.userId)

        /**
         * Get an intent to start the issue recording service.
         *
         * @param context Context from the requesting activity
         */
        fun getStartIntent(
            context: Context,
            traceConfig: TraceConfig,
            screenRecord: Boolean,
            bugReport: Boolean,
        ): Intent =
            Intent(context, IssueRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(INTENT_EXTRA_TRACE_TYPE, traceConfig)
                .putExtra(EXTRA_SCREEN_RECORD, screenRecord)
                .putExtra(EXTRA_BUG_REPORT, bugReport)
    }
}

private class IrsStrings(private val res: Resources) : RecordingServiceStrings(res) {
    override val title
        get() = res.getString(R.string.issuerecord_title)

    override val notificationChannelDescription
        get() = res.getString(R.string.issuerecord_channel_description)

    override val startErrorResId
        get() = R.string.issuerecord_start_error

    override val startError
        get() = res.getString(R.string.issuerecord_start_error)

    override val saveErrorResId
        get() = R.string.issuerecord_save_error

    override val saveError
        get() = res.getString(R.string.issuerecord_save_error)

    override val ongoingRecording
        get() = res.getString(R.string.issuerecord_ongoing_screen_only)

    override val backgroundProcessingLabel
        get() = res.getString(R.string.issuerecord_background_processing_label)

    override val saveTitle
        get() = res.getString(R.string.issuerecord_save_title)
}
