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
import android.content.pm.LauncherApps
import android.content.res.Resources
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.util.Log
import androidx.core.content.FileProvider
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.qualifiers.LongRunning
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.res.R
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.RecordingService
import com.android.systemui.screenrecord.RecordingServiceStrings
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.traceur.FileSender
import com.android.traceur.TraceUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.jvm.optionals.getOrElse

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
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val panelInteractor: PanelInteractor,
    private val issueRecordingState: IssueRecordingState,
    private val iActivityManager: IActivityManager,
    private val launcherApps: LauncherApps,
) :
    RecordingService(
        controller,
        bgExecutor,
        handler,
        uiEventLogger,
        notificationManager,
        userContextProvider,
        keyguardDismissUtil
    ) {

    override fun getTag(): String = TAG

    override fun getChannelId(): String = CHANNEL_ID

    override fun provideRecordingServiceStrings(): RecordingServiceStrings = IrsStrings(resources)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                TraceUtils.traceStart(
                    contentResolver,
                    DEFAULT_TRACE_TAGS,
                    DEFAULT_BUFFER_SIZE,
                    DEFAULT_IS_INCLUDING_WINSCOPE,
                    DEFAULT_IS_INCLUDING_APP_TRACE,
                    DEFAULT_IS_LONG_TRACE,
                    DEFAULT_ATTACH_TO_BUGREPORT,
                    DEFAULT_MAX_TRACE_SIZE,
                    DEFAULT_MAX_TRACE_DURATION_IN_MINUTES
                )
                issueRecordingState.isRecording = true
                if (!intent.getBooleanExtra(EXTRA_SCREEN_RECORD, false)) {
                    // If we don't want to record the screen, the ACTION_SHOW_START_NOTIF action
                    // will circumvent the RecordingService's screen recording start code.
                    return super.onStartCommand(Intent(ACTION_SHOW_START_NOTIF), flags, startId)
                }
            }
            ACTION_STOP,
            ACTION_STOP_NOTIF -> {
                // ViewCapture needs to save it's data before it is disabled, or else the data will
                // be lost. This is expected to change in the near future, and when that happens
                // this line should be removed.
                launcherApps.saveViewCaptureData()
                TraceUtils.traceStop(contentResolver)
                issueRecordingState.isRecording = false
            }
            ACTION_SHARE -> {
                bgExecutor.execute {
                    mNotificationManager.cancelAsUser(
                        null,
                        mNotificationId,
                        UserHandle(mUserContextTracker.userContext.userId)
                    )

                    val screenRecording = intent.getParcelableExtra(EXTRA_PATH, Uri::class.java)
                    if (issueRecordingState.takeBugReport) {
                        iActivityManager.requestBugReportWithExtraAttachment(screenRecording)
                    } else {
                        shareRecording(screenRecording)
                    }
                }

                dialogTransitionAnimator.disableAllCurrentDialogsExitAnimations()
                panelInteractor.collapsePanels()

                // Unlike all other actions, action_share has different behavior for the screen
                // recording qs tile than it does for the record issue qs tile. Return sticky to
                // avoid running any of the base class' code for this action.
                return START_STICKY
            }
            else -> {}
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun shareRecording(screenRecording: Uri?) {
        val traces =
            TraceUtils.traceDump(contentResolver, TRACE_FILE_NAME).getOrElse {
                Log.v(
                    TAG,
                    "Traces were not present. This can happen if users double" +
                        "click on share notification. Traces are cleaned up after sharing" +
                        "so they won't be present for the 2nd share attempt."
                )
                return
            }
        val perfetto = FileProvider.getUriForFile(this, AUTHORITY, traces.first())
        val urisToShare = mutableListOf(perfetto)
        traces.removeFirst()

        getZipWinscopeFileUri(traces)?.let { urisToShare.add(it) }
        screenRecording?.let { urisToShare.add(it) }

        val sendIntent =
            FileSender.buildSendIntent(this, urisToShare).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // TODO: Debug why the notification shade isn't closing upon starting the BetterBug activity
        mKeyguardDismissUtil.executeWhenUnlocked(
            {
                startActivity(sendIntent)
                false
            },
            false,
            false
        )
    }

    private fun getZipWinscopeFileUri(traceFiles: List<File>): Uri? {
        try {
            externalCacheDir?.mkdirs()
            val outZip: File = File.createTempFile(TEMP_FILE_PREFIX, ZIP_SUFFIX, externalCacheDir)
            ZipOutputStream(FileOutputStream(outZip)).use { os ->
                traceFiles.forEach { file ->
                    os.putNextEntry(ZipEntry(file.name))
                    Files.copy(file.toPath(), os)
                    os.closeEntry()
                }
            }
            return FileProvider.getUriForFile(this, AUTHORITY, outZip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to zip and package Recordings. Cannot share with BetterBug.", e)
            return null
        }
    }

    companion object {
        private const val TAG = "IssueRecordingService"
        private const val CHANNEL_ID = "issue_record"
        private const val EXTRA_SCREEN_RECORD = "extra_screenRecord"
        private const val EXTRA_WINSCOPE_TRACING = "extra_winscopeTracing"
        private const val ZIP_SUFFIX = ".zip"
        private const val TEMP_FILE_PREFIX = "winscope_recordings"

        private val DEFAULT_TRACE_TAGS = listOf<String>()
        private const val DEFAULT_BUFFER_SIZE = 16384
        private const val DEFAULT_IS_INCLUDING_WINSCOPE = true
        private const val DEFAULT_IS_LONG_TRACE = false
        private const val DEFAULT_IS_INCLUDING_APP_TRACE = true
        private const val DEFAULT_ATTACH_TO_BUGREPORT = true
        private const val DEFAULT_MAX_TRACE_SIZE = 10240
        private const val DEFAULT_MAX_TRACE_DURATION_IN_MINUTES = 30

        private val TRACE_FILE_NAME = TraceUtils.getOutputFilename(TraceUtils.RecordingType.TRACE)
        private const val AUTHORITY = "com.android.systemui.fileprovider"

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
            screenRecord: Boolean,
            winscopeTracing: Boolean,
        ): Intent =
            Intent(context, IssueRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCREEN_RECORD, screenRecord)
                .putExtra(EXTRA_WINSCOPE_TRACING, winscopeTracing)
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
