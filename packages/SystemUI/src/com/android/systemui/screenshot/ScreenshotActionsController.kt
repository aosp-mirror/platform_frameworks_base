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
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.screenshot.ui.viewmodel.PreviewAction
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * Responsible for obtaining the actions for each screenshot and sending them to the view model.
 * Ensures that only actions from screenshots that are currently being shown are added to the view
 * model.
 */
class ScreenshotActionsController
@AssistedInject
constructor(
    private val viewModel: ScreenshotViewModel,
    private val actionsProviderFactory: ScreenshotActionsProvider.Factory,
    @Assisted val actionExecutor: ActionExecutor
) {
    private val actionProviders: MutableMap<UUID, ScreenshotActionsProvider> = mutableMapOf()
    private var currentScreenshotId: UUID? = null

    fun setCurrentScreenshot(screenshot: ScreenshotData): UUID {
        val screenshotId = UUID.randomUUID()
        currentScreenshotId = screenshotId
        actionProviders[screenshotId] =
            actionsProviderFactory.create(
                screenshotId,
                screenshot,
                actionExecutor,
                ActionsCallback(screenshotId),
            )
        return screenshotId
    }

    fun endScreenshotSession() {
        currentScreenshotId = null
    }

    fun onAssistContent(screenshotId: UUID, assistContent: AssistContent?) {
        actionProviders[screenshotId]?.onAssistContent(assistContent)
    }

    fun onScrollChipReady(screenshotId: UUID, onClick: Runnable) {
        if (screenshotId == currentScreenshotId) {
            actionProviders[screenshotId]?.onScrollChipReady(onClick)
        }
    }

    fun onScrollChipInvalidated() {
        for (provider in actionProviders.values) {
            provider.onScrollChipInvalidated()
        }
    }

    fun setCompletedScreenshot(screenshotId: UUID, result: ScreenshotSavedResult) {
        if (screenshotId == currentScreenshotId) {
            actionProviders[screenshotId]?.setCompletedScreenshot(result)
        }
    }

    @AssistedFactory
    interface Factory {
        fun getController(actionExecutor: ActionExecutor): ScreenshotActionsController
    }

    inner class ActionsCallback(private val screenshotId: UUID) {
        fun providePreviewAction(previewAction: PreviewAction) {
            if (screenshotId == currentScreenshotId) {
                viewModel.setPreviewAction(previewAction)
            }
        }

        fun provideActionButton(
            appearance: ActionButtonAppearance,
            showDuringEntrance: Boolean,
            onClick: () -> Unit
        ): Int {
            if (screenshotId == currentScreenshotId) {
                return viewModel.addAction(appearance, showDuringEntrance, onClick)
            }
            return 0
        }

        fun updateActionButtonAppearance(buttonId: Int, appearance: ActionButtonAppearance) {
            if (screenshotId == currentScreenshotId) {
                viewModel.updateActionAppearance(buttonId, appearance)
            }
        }

        fun updateActionButtonVisibility(buttonId: Int, visible: Boolean) {
            if (screenshotId == currentScreenshotId) {
                viewModel.setActionVisibility(buttonId, visible)
            }
        }
    }
}
