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
import android.app.ExitTransitionCoordinator
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Pair
import androidx.appcompat.content.res.AppCompatResources
import com.android.app.tracing.coroutines.launch
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.res.R
import com.android.systemui.screenshot.ActionIntentCreator.createEdit
import com.android.systemui.screenshot.ActionIntentCreator.createShareWithSubject
import com.android.systemui.screenshot.ScreenshotController.SavedImageData
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonViewModel
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope

/**
 * Provides actions for screenshots. This class can be overridden by a vendor-specific SysUI
 * implementation.
 */
interface ScreenshotActionsProvider {
    fun setCompletedScreenshot(result: SavedImageData)
    fun isPendingSharedTransition(): Boolean

    interface Factory {
        fun create(
            request: ScreenshotData,
            windowTransition: () -> Pair<ActivityOptions, ExitTransitionCoordinator>,
        ): ScreenshotActionsProvider
    }
}

class DefaultScreenshotActionsProvider
@AssistedInject
constructor(
    private val context: Context,
    private val viewModel: ScreenshotViewModel,
    private val actionExecutor: ActionIntentExecutor,
    @Application private val applicationScope: CoroutineScope,
    @Assisted val request: ScreenshotData,
    @Assisted val windowTransition: () -> Pair<ActivityOptions, ExitTransitionCoordinator>,
) : ScreenshotActionsProvider {
    private var pendingAction: ((SavedImageData) -> Unit)? = null
    private var result: SavedImageData? = null
    private var isPendingSharedTransition = false

    init {
        viewModel.setPreviewAction {
            debugLog(LogConfig.DEBUG_ACTIONS) { "Preview tapped" }
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
                onDeferrableActionTapped { result ->
                    startSharedTransition(createShareWithSubject(result.uri, result.subject), false)
                }
            }
        )
    }

    override fun setCompletedScreenshot(result: SavedImageData) {
        if (this.result != null) {
            Log.e(TAG, "Got a second completed screenshot for existing request!")
            return
        }
        if (result.uri == null || result.owner == null || result.subject == null) {
            Log.e(TAG, "Invalid result provided!")
            return
        }
        this.result = result
        pendingAction?.invoke(result)
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

    @AssistedFactory
    interface Factory : ScreenshotActionsProvider.Factory {
        override fun create(
            request: ScreenshotData,
            windowTransition: () -> Pair<ActivityOptions, ExitTransitionCoordinator>,
        ): DefaultScreenshotActionsProvider
    }

    companion object {
        private const val TAG = "ScreenshotActionsProvider"
    }
}
