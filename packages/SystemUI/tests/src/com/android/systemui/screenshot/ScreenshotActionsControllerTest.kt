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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import java.util.UUID
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ScreenshotActionsControllerTest : SysuiTestCase() {
    private val screenshotData = mock<ScreenshotData>()
    private val actionExecutor = mock<ActionExecutor>()
    private val viewModel = mock<ScreenshotViewModel>()
    private val onClick = mock<() -> Unit>()

    private lateinit var actionsController: ScreenshotActionsController
    private lateinit var fakeActionsProvider1: FakeActionsProvider
    private lateinit var fakeActionsProvider2: FakeActionsProvider
    private val actionsProviderFactory =
        object : ScreenshotActionsProvider.Factory {
            var isFirstCall = true
            override fun create(
                requestId: UUID,
                request: ScreenshotData,
                actionExecutor: ActionExecutor,
                actionsCallback: ScreenshotActionsController.ActionsCallback
            ): ScreenshotActionsProvider {
                return if (isFirstCall) {
                    isFirstCall = false
                    fakeActionsProvider1 = FakeActionsProvider(actionsCallback)
                    fakeActionsProvider1
                } else {
                    fakeActionsProvider2 = FakeActionsProvider(actionsCallback)
                    fakeActionsProvider2
                }
            }
        }

    @Before
    fun setUp() {
        actionsController =
            ScreenshotActionsController(viewModel, actionsProviderFactory, actionExecutor)
    }

    @Test
    fun setPreview_onCurrentScreenshot_updatesViewModel() {
        actionsController.setCurrentScreenshot(screenshotData)
        fakeActionsProvider1.callPreview(onClick)

        verify(viewModel).setPreviewAction(onClick)
    }

    @Test
    fun setPreview_onNonCurrentScreenshot_doesNotUpdateViewModel() {
        actionsController.setCurrentScreenshot(screenshotData)
        actionsController.setCurrentScreenshot(screenshotData)
        fakeActionsProvider1.callPreview(onClick)

        verify(viewModel, never()).setPreviewAction(any())
    }

    class FakeActionsProvider(
        private val actionsCallback: ScreenshotActionsController.ActionsCallback
    ) : ScreenshotActionsProvider {

        fun callPreview(onClick: () -> Unit) {
            actionsCallback.providePreviewAction(onClick)
        }

        override fun onScrollChipReady(onClick: Runnable) {}

        override fun onScrollChipInvalidated() {}

        override fun setCompletedScreenshot(result: ScreenshotSavedResult) {}
    }
}
