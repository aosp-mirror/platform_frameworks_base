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

package com.android.systemui.screenshot

import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.ExitTransitionCoordinator
import android.app.PendingIntent
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.provider.DeviceConfig
import android.util.Log
import android.util.Pair
import androidx.appcompat.content.res.AppCompatResources
import com.android.app.tracing.coroutines.launch
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.res.R
import com.android.systemui.screenshot.ActionIntentCreator.createEdit
import com.android.systemui.screenshot.ActionIntentCreator.createShareWithSubject
import com.android.systemui.screenshot.ScreenshotController.SavedImageData
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_EDIT_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_PREVIEW_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_SHARE_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_SMART_ACTION_TAPPED
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonViewModel
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope

/**
 * Provides actions for screenshots. This class can be overridden by a vendor-specific SysUI
 * implementation.
 */
interface ScreenshotActionsProvider {
    fun setCompletedScreenshot(result: SavedImageData)
    fun isPendingSharedTransition(): Boolean

    fun onAssistContentAvailable(assistContent: AssistContent) {}

    interface Factory {
        fun create(
            request: ScreenshotData,
            requestId: String,
            windowTransition: () -> Pair<ActivityOptions, ExitTransitionCoordinator>,
            requestDismissal: () -> Unit,
        ): ScreenshotActionsProvider
    }
}

class DefaultScreenshotActionsProvider
@AssistedInject
constructor(
    private val context: Context,
    private val viewModel: ScreenshotViewModel,
    private val actionExecutor: ActionIntentExecutor,
    private val smartActionsProvider: SmartActionsProvider,
    private val uiEventLogger: UiEventLogger,
    @Application private val applicationScope: CoroutineScope,
    @Assisted val request: ScreenshotData,
    @Assisted val requestId: String,
    @Assisted val windowTransition: () -> Pair<ActivityOptions, ExitTransitionCoordinator>,
    @Assisted val requestDismissal: () -> Unit,
) : ScreenshotActionsProvider {
    private var pendingAction: ((SavedImageData) -> Unit)? = null
    private var result: SavedImageData? = null
    private var isPendingSharedTransition = false

    init {
        viewModel.setPreviewAction {
            debugLog(LogConfig.DEBUG_ACTIONS) { "Preview tapped" }
            uiEventLogger.log(SCREENSHOT_PREVIEW_TAPPED, 0, request.packageNameString)
            onDeferrableActionTapped { result ->
                startSharedTransition(createEdit(result.uri, context), true)
            }
        }
        viewModel.addAction(
            ActionButtonViewModel(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_edit),
                context.resources.getString(R.string.screenshot_edit_label),
                context.resources.getString(R.string.screenshot_edit_description),
            ) {
                debugLog(LogConfig.DEBUG_ACTIONS) { "Edit tapped" }
                uiEventLogger.log(SCREENSHOT_EDIT_TAPPED, 0, request.packageNameString)
                onDeferrableActionTapped { result ->
                    startSharedTransition(createEdit(result.uri, context), true)
                }
            }
        )
        viewModel.addAction(
            ActionButtonViewModel(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_share),
                context.resources.getString(R.string.screenshot_share_label),
                context.resources.getString(R.string.screenshot_share_description),
            ) {
                debugLog(LogConfig.DEBUG_ACTIONS) { "Share tapped" }
                uiEventLogger.log(SCREENSHOT_SHARE_TAPPED, 0, request.packageNameString)
                onDeferrableActionTapped { result ->
                    startSharedTransition(createShareWithSubject(result.uri, result.subject), false)
                }
            }
        )
        if (smartActionsEnabled(request.userHandle ?: Process.myUserHandle())) {
            smartActionsProvider.requestQuickShare(request, requestId) { quickShare ->
                if (!quickShare.actionIntent.isImmutable) {
                    viewModel.addAction(
                        ActionButtonViewModel(
                            quickShare.getIcon().loadDrawable(context),
                            quickShare.title,
                            quickShare.title,
                        ) {
                            debugLog(LogConfig.DEBUG_ACTIONS) { "Quickshare tapped" }
                            onDeferrableActionTapped { result ->
                                uiEventLogger.log(
                                    SCREENSHOT_SMART_ACTION_TAPPED,
                                    0,
                                    request.packageNameString
                                )
                                sendPendingIntent(
                                    smartActionsProvider
                                        .wrapIntent(
                                            quickShare,
                                            result.uri,
                                            result.subject,
                                            requestId
                                        )
                                        .actionIntent
                                )
                            }
                        }
                    )
                } else {
                    Log.w(TAG, "Received immutable quick share pending intent; ignoring")
                }
            }
        }
    }

    override fun setCompletedScreenshot(result: SavedImageData) {
        if (this.result != null) {
            Log.e(TAG, "Got a second completed screenshot for existing request!")
            return
        }
        if (result.uri == null || result.owner == null || result.imageTime == null) {
            Log.e(TAG, "Invalid result provided!")
            return
        }
        if (result.subject == null) {
            result.subject = getSubjectString(result.imageTime)
        }
        this.result = result
        pendingAction?.invoke(result)
        if (smartActionsEnabled(result.owner)) {
            smartActionsProvider.requestSmartActions(request, requestId, result) { smartActions ->
                viewModel.addActions(
                    smartActions.map {
                        ActionButtonViewModel(
                            it.getIcon().loadDrawable(context),
                            it.title,
                            it.title,
                        ) {
                            sendPendingIntent(it.actionIntent)
                        }
                    }
                )
            }
        }
    }

    override fun isPendingSharedTransition(): Boolean {
        return isPendingSharedTransition
    }

    private fun onDeferrableActionTapped(onResult: (SavedImageData) -> Unit) {
        result?.let { onResult.invoke(it) } ?: run { pendingAction = onResult }
    }

    private fun startSharedTransition(intent: Intent, overrideTransition: Boolean) {
        val user =
            result?.owner
                ?: run {
                    Log.wtf(TAG, "User handle not provided in screenshot result! Result: $result")
                    return
                }
        isPendingSharedTransition = true
        applicationScope.launch("$TAG#launchIntentAsync") {
            actionExecutor.launchIntent(intent, windowTransition.invoke(), user, overrideTransition)
        }
    }

    private fun sendPendingIntent(pendingIntent: PendingIntent) {
        try {
            val options = BroadcastOptions.makeBasic()
            options.setInteractive(true)
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            pendingIntent.send(options.toBundle())
            requestDismissal.invoke()
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Intent cancelled", e)
        }
    }

    private fun smartActionsEnabled(user: UserHandle): Boolean {
        val savingToOtherUser = user != Process.myUserHandle()
        return !savingToOtherUser &&
            DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS,
                true
            )
    }

    private fun getSubjectString(imageTime: Long): String {
        val subjectDate = DateFormat.getDateTimeInstance().format(Date(imageTime))
        return String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate)
    }

    @AssistedFactory
    interface Factory : ScreenshotActionsProvider.Factory {
        override fun create(
            request: ScreenshotData,
            requestId: String,
            windowTransition: () -> Pair<ActivityOptions, ExitTransitionCoordinator>,
            requestDismissal: () -> Unit,
        ): DefaultScreenshotActionsProvider
    }

    companion object {
        private const val TAG = "ScreenshotActionsProvider"
        private const val SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)"
    }
}
