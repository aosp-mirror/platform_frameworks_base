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

import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.view.accessibility.AccessibilityManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DefaultScreenshotActionsProviderTest : SysuiTestCase() {
    private val actionExecutor = mock<ActionExecutor>()
    private val accessibilityManager = mock<AccessibilityManager>()
    private val uiEventLogger = mock<UiEventLogger>()

    private val request = ScreenshotData.forTesting()
    private val validResult = ScreenshotSavedResult(Uri.EMPTY, Process.myUserHandle(), 0)

    private lateinit var viewModel: ScreenshotViewModel
    private lateinit var actionsProvider: ScreenshotActionsProvider

    @Before
    fun setUp() {
        viewModel = ScreenshotViewModel(accessibilityManager)
        request.userHandle = UserHandle.OWNER
    }

    @Test
    fun previewActionAccessed_beforeScreenshotCompleted_doesNothing() {
        actionsProvider = createActionsProvider()

        assertNotNull(viewModel.previewAction.value)
        viewModel.previewAction.value!!.invoke()
        verifyNoMoreInteractions(actionExecutor)
    }

    @Test
    fun actionButtonsAccessed_beforeScreenshotCompleted_doesNothing() {
        actionsProvider = createActionsProvider()

        assertThat(viewModel.actions.value.size).isEqualTo(2)
        val firstAction = viewModel.actions.value[0]
        assertThat(firstAction.onClicked).isNotNull()
        val secondAction = viewModel.actions.value[1]
        assertThat(secondAction.onClicked).isNotNull()
        firstAction.onClicked!!.invoke()
        secondAction.onClicked!!.invoke()
        verifyNoMoreInteractions(actionExecutor)
    }

    @Test
    fun actionAccessed_withResult_launchesIntent() = runTest {
        actionsProvider = createActionsProvider()

        actionsProvider.setCompletedScreenshot(validResult)
        viewModel.actions.value[0].onClicked!!.invoke()

        verify(uiEventLogger).log(eq(ScreenshotEvent.SCREENSHOT_EDIT_TAPPED), eq(0), eq(""))
        val intentCaptor = argumentCaptor<Intent>()
        verify(actionExecutor)
            .startSharedTransition(capture(intentCaptor), eq(Process.myUserHandle()), eq(true))
        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_EDIT)
    }

    @Test
    fun actionAccessed_whilePending_launchesMostRecentAction() = runTest {
        actionsProvider = createActionsProvider()

        viewModel.actions.value[0].onClicked!!.invoke()
        viewModel.previewAction.value!!.invoke()
        viewModel.actions.value[1].onClicked!!.invoke()
        actionsProvider.setCompletedScreenshot(validResult)

        verify(uiEventLogger).log(eq(ScreenshotEvent.SCREENSHOT_SHARE_TAPPED), eq(0), eq(""))
        val intentCaptor = argumentCaptor<Intent>()
        verify(actionExecutor)
            .startSharedTransition(capture(intentCaptor), eq(Process.myUserHandle()), eq(false))
        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_CHOOSER)
    }

    @Test
    fun scrollChipClicked_callsOnClick() = runTest {
        actionsProvider = createActionsProvider()

        val onScrollClick = mock<Runnable>()
        val numActions = viewModel.actions.value.size
        actionsProvider.onScrollChipReady(onScrollClick)
        viewModel.actions.value[numActions].onClicked!!.invoke()

        verify(onScrollClick).run()
    }

    @Test
    fun scrollChipClicked_afterInvalidate_doesNothing() = runTest {
        actionsProvider = createActionsProvider()

        val onScrollClick = mock<Runnable>()
        val numActions = viewModel.actions.value.size
        actionsProvider.onScrollChipReady(onScrollClick)
        actionsProvider.onScrollChipInvalidated()
        viewModel.actions.value[numActions].onClicked!!.invoke()

        verify(onScrollClick, never()).run()
    }

    @Test
    fun scrollChipClicked_afterUpdate_runsNewAction() = runTest {
        actionsProvider = createActionsProvider()

        val onScrollClick = mock<Runnable>()
        val onScrollClick2 = mock<Runnable>()
        val numActions = viewModel.actions.value.size
        actionsProvider.onScrollChipReady(onScrollClick)
        actionsProvider.onScrollChipInvalidated()
        actionsProvider.onScrollChipReady(onScrollClick2)
        viewModel.actions.value[numActions].onClicked!!.invoke()

        verify(onScrollClick2).run()
        verify(onScrollClick, never()).run()
    }

    private fun createActionsProvider(): ScreenshotActionsProvider {
        return DefaultScreenshotActionsProvider(
            context,
            viewModel,
            uiEventLogger,
            request,
            "testid",
            actionExecutor
        )
    }
}
