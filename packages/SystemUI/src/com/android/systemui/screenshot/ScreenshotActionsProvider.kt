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

import android.app.assist.AssistContent
import android.content.Context
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.android.internal.logging.UiEventLogger
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.res.R
import com.android.systemui.screenshot.ActionIntentCreator.createEdit
import com.android.systemui.screenshot.ActionIntentCreator.createShareWithSubject
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_EDIT_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_PREVIEW_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_SHARE_TAPPED
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.screenshot.ui.viewmodel.PreviewAction
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * Provides actions for screenshots. This class can be overridden by a vendor-specific SysUI
 * implementation.
 */
interface ScreenshotActionsProvider {
    fun onScrollChipReady(onClick: Runnable)

    fun onScrollChipInvalidated()

    fun setCompletedScreenshot(result: ScreenshotSavedResult)

    /**
     * Provide the AssistContent for the focused task if available, null if the focused task isn't
     * known or didn't return data.
     */
    fun onAssistContent(assistContent: AssistContent?) {}

    interface Factory {
        fun create(
            requestId: UUID,
            request: ScreenshotData,
            actionExecutor: ActionExecutor,
            actionsCallback: ScreenshotActionsController.ActionsCallback,
        ): ScreenshotActionsProvider
    }
}

class DefaultScreenshotActionsProvider
@AssistedInject
constructor(
    private val context: Context,
    private val uiEventLogger: UiEventLogger,
    @Assisted val requestId: UUID,
    @Assisted val request: ScreenshotData,
    @Assisted val actionExecutor: ActionExecutor,
    @Assisted val actionsCallback: ScreenshotActionsController.ActionsCallback,
) : ScreenshotActionsProvider {
    private var addedScrollChip = false
    private var onScrollClick: Runnable? = null
    private var pendingAction: ((ScreenshotSavedResult) -> Unit)? = null
    private var result: ScreenshotSavedResult? = null

    init {
        actionsCallback.providePreviewAction(
            PreviewAction(context.resources.getString(R.string.screenshot_edit_description)) {
                debugLog(LogConfig.DEBUG_ACTIONS) { "Preview tapped" }
                uiEventLogger.log(SCREENSHOT_PREVIEW_TAPPED, 0, request.packageNameString)
                onDeferrableActionTapped { result ->
                    actionExecutor.startSharedTransition(
                        createEdit(result.uri, context),
                        result.user,
                        true
                    )
                }
            }
        )

        actionsCallback.provideActionButton(
            ActionButtonAppearance(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_share),
                context.resources.getString(R.string.screenshot_share_label),
                context.resources.getString(R.string.screenshot_share_description),
            ),
            showDuringEntrance = true,
        ) {
            debugLog(LogConfig.DEBUG_ACTIONS) { "Share tapped" }
            uiEventLogger.log(SCREENSHOT_SHARE_TAPPED, 0, request.packageNameString)
            onDeferrableActionTapped { result ->
                actionExecutor.startSharedTransition(
                    createShareWithSubject(result.uri, result.subject),
                    result.user,
                    false
                )
            }
        }

        actionsCallback.provideActionButton(
            ActionButtonAppearance(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_edit),
                context.resources.getString(R.string.screenshot_edit_label),
                context.resources.getString(R.string.screenshot_edit_description),
            ),
            showDuringEntrance = true,
        ) {
            debugLog(LogConfig.DEBUG_ACTIONS) { "Edit tapped" }
            uiEventLogger.log(SCREENSHOT_EDIT_TAPPED, 0, request.packageNameString)
            onDeferrableActionTapped { result ->
                actionExecutor.startSharedTransition(
                    createEdit(result.uri, context),
                    result.user,
                    true
                )
            }
        }
    }

    override fun onScrollChipReady(onClick: Runnable) {
        onScrollClick = onClick
        if (!addedScrollChip) {
            actionsCallback.provideActionButton(
                ActionButtonAppearance(
                    AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_scroll),
                    context.resources.getString(R.string.screenshot_scroll_label),
                    context.resources.getString(R.string.screenshot_scroll_label),
                ),
                showDuringEntrance = true,
            ) {
                onScrollClick?.run()
            }
            addedScrollChip = true
        }
    }

    override fun onScrollChipInvalidated() {
        onScrollClick = null
    }

    override fun setCompletedScreenshot(result: ScreenshotSavedResult) {
        if (this.result != null) {
            Log.e(TAG, "Got a second completed screenshot for existing request!")
            return
        }
        this.result = result
        pendingAction?.invoke(result)
    }

    private fun onDeferrableActionTapped(onResult: (ScreenshotSavedResult) -> Unit) {
        result?.let { onResult.invoke(it) } ?: run { pendingAction = onResult }
    }

    @AssistedFactory
    interface Factory : ScreenshotActionsProvider.Factory {
        override fun create(
            requestId: UUID,
            request: ScreenshotData,
            actionExecutor: ActionExecutor,
            actionsCallback: ScreenshotActionsController.ActionsCallback,
        ): DefaultScreenshotActionsProvider
    }

    companion object {
        private const val TAG = "ScreenshotActionsProvider"
    }
}
