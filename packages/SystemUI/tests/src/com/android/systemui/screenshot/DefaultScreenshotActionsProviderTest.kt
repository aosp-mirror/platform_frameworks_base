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
import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.view.accessibility.AccessibilityManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Ignore
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.verifyBlocking

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DefaultScreenshotActionsProviderTest : SysuiTestCase() {
    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(mainDispatcher)

    private val actionIntentExecutor = mock<ActionIntentExecutor>()
    private val accessibilityManager = mock<AccessibilityManager>()
    private val transition = mock<android.util.Pair<ActivityOptions, ExitTransitionCoordinator>>()

    private val request = ScreenshotData.forTesting()
    private val invalidResult = ScreenshotController.SavedImageData()
    private val validResult =
        ScreenshotController.SavedImageData().apply {
            uri = Uri.EMPTY
            owner = UserHandle.CURRENT
            subject = "Test"
        }

    private lateinit var viewModel: ScreenshotViewModel
    private lateinit var actionsProvider: ScreenshotActionsProvider

    @Before
    fun setUp() {
        viewModel = ScreenshotViewModel(accessibilityManager)
        actionsProvider =
            DefaultScreenshotActionsProvider(
                context,
                viewModel,
                actionIntentExecutor,
                testScope,
                request
            ) {
                transition
            }
    }

    @Test
    fun previewActionAccessed_beforeScreenshotCompleted_doesNothing() {
        assertNotNull(viewModel.previewAction.value)
        viewModel.previewAction.value!!.invoke()
        verifyNoMoreInteractions(actionIntentExecutor)
    }

    @Test
    fun actionButtonsAccessed_beforeScreenshotCompleted_doesNothing() {
        assertThat(viewModel.actions.value.size).isEqualTo(2)
        val firstAction = viewModel.actions.value[0]
        assertThat(firstAction.onClicked).isNotNull()
        val secondAction = viewModel.actions.value[1]
        assertThat(secondAction.onClicked).isNotNull()
        firstAction.onClicked!!.invoke()
        secondAction.onClicked!!.invoke()
        verifyNoMoreInteractions(actionIntentExecutor)
    }

    @Test
    fun actionAccessed_withInvalidResult_doesNothing() {
        actionsProvider.setCompletedScreenshot(invalidResult)
        viewModel.previewAction.value!!.invoke()
        viewModel.actions.value[1].onClicked!!.invoke()

        verifyNoMoreInteractions(actionIntentExecutor)
    }

    @Test
    @Ignore("b/332526567")
    fun actionAccessed_withResult_launchesIntent() = runTest {
        actionsProvider.setCompletedScreenshot(validResult)
        viewModel.actions.value[0].onClicked!!.invoke()
        scheduler.advanceUntilIdle()

        val intentCaptor = argumentCaptor<Intent>()
        verifyBlocking(actionIntentExecutor) {
            launchIntent(capture(intentCaptor), eq(transition), eq(UserHandle.CURRENT), eq(true))
        }
        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_EDIT)
    }

    @Test
    @Ignore("b/332526567")
    fun actionAccessed_whilePending_launchesMostRecentAction() = runTest {
        viewModel.actions.value[0].onClicked!!.invoke()
        viewModel.previewAction.value!!.invoke()
        viewModel.actions.value[1].onClicked!!.invoke()
        actionsProvider.setCompletedScreenshot(validResult)
        scheduler.advanceUntilIdle()

        val intentCaptor = argumentCaptor<Intent>()
        verifyBlocking(actionIntentExecutor) {
            launchIntent(capture(intentCaptor), eq(transition), eq(UserHandle.CURRENT), eq(false))
        }
        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_CHOOSER)
    }
}
