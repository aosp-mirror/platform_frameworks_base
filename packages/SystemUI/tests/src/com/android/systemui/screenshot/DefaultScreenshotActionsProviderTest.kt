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
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.ui.viewmodel.PreviewAction
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DefaultScreenshotActionsProviderTest : SysuiTestCase() {
    private val actionExecutor = mock<ActionExecutor>()
    private val uiEventLogger = mock<UiEventLogger>()
    private val actionsCallback = mock<ScreenshotActionsController.ActionsCallback>()

    private val request = ScreenshotData.forTesting()
    private val validResult = ScreenshotSavedResult(Uri.EMPTY, Process.myUserHandle(), 0)

    private lateinit var actionsProvider: ScreenshotActionsProvider

    @Before
    fun setUp() {
        request.userHandle = UserHandle.OWNER
    }

    @Test
    fun previewActionAccessed_beforeScreenshotCompleted_doesNothing() {
        actionsProvider = createActionsProvider()

        val previewActionCaptor = argumentCaptor<PreviewAction>()
        verify(actionsCallback).providePreviewAction(previewActionCaptor.capture())
        previewActionCaptor.firstValue.onClick.invoke()
        verifyNoMoreInteractions(actionExecutor)
    }

    @Test
    fun actionButtonsAccessed_beforeScreenshotCompleted_doesNothing() {
        actionsProvider = createActionsProvider()

        val actionButtonCaptor = argumentCaptor<() -> Unit>()
        verify(actionsCallback, times(2))
            .provideActionButton(any(), any(), actionButtonCaptor.capture())
        val firstAction = actionButtonCaptor.firstValue
        val secondAction = actionButtonCaptor.secondValue
        firstAction.invoke()
        secondAction.invoke()
        verifyNoMoreInteractions(actionExecutor)
    }

    @Test
    fun actionAccessed_withResult_launchesIntent() = runTest {
        actionsProvider = createActionsProvider()

        actionsProvider.setCompletedScreenshot(validResult)

        val actionButtonCaptor = argumentCaptor<() -> Unit>()
        verify(actionsCallback, times(2))
            .provideActionButton(any(), any(), actionButtonCaptor.capture())
        actionButtonCaptor.firstValue.invoke()

        verify(uiEventLogger).log(eq(ScreenshotEvent.SCREENSHOT_SHARE_TAPPED), eq(0), eq(""))
        val intentCaptor = argumentCaptor<Intent>()
        verify(actionExecutor)
            .startSharedTransition(intentCaptor.capture(), eq(Process.myUserHandle()), eq(false))
        assertThat(intentCaptor.firstValue.action).isEqualTo(Intent.ACTION_CHOOSER)
    }

    @Test
    fun actionAccessed_whilePending_launchesMostRecentAction() = runTest {
        actionsProvider = createActionsProvider()

        val previewActionCaptor = argumentCaptor<PreviewAction>()
        verify(actionsCallback).providePreviewAction(previewActionCaptor.capture())
        val actionButtonCaptor = argumentCaptor<() -> Unit>()
        verify(actionsCallback, times(2))
            .provideActionButton(any(), any(), actionButtonCaptor.capture())

        actionButtonCaptor.firstValue.invoke()
        previewActionCaptor.firstValue.onClick.invoke()
        actionButtonCaptor.secondValue.invoke()
        actionsProvider.setCompletedScreenshot(validResult)

        verify(uiEventLogger).log(eq(ScreenshotEvent.SCREENSHOT_EDIT_TAPPED), eq(0), eq(""))
        val intentCaptor = argumentCaptor<Intent>()
        verify(actionExecutor)
            .startSharedTransition(intentCaptor.capture(), eq(Process.myUserHandle()), eq(true))
        assertThat(intentCaptor.firstValue.action).isEqualTo(Intent.ACTION_EDIT)
    }

    @Test
    fun scrollChipClicked_callsOnClick() = runTest {
        actionsProvider = createActionsProvider()

        val onScrollClick = mock<Runnable>()
        actionsProvider.onScrollChipReady(onScrollClick)
        val actionButtonCaptor = argumentCaptor<() -> Unit>()
        // share, edit, scroll
        verify(actionsCallback, times(3))
            .provideActionButton(any(), any(), actionButtonCaptor.capture())
        actionButtonCaptor.thirdValue.invoke()

        verify(onScrollClick).run()
    }

    @Test
    fun scrollChipClicked_afterInvalidate_doesNothing() = runTest {
        actionsProvider = createActionsProvider()

        val onScrollClick = mock<Runnable>()
        actionsProvider.onScrollChipReady(onScrollClick)
        val actionButtonCaptor = argumentCaptor<() -> Unit>()
        actionsProvider.onScrollChipInvalidated()
        // share, edit, scroll
        verify(actionsCallback, times(3))
            .provideActionButton(any(), any(), actionButtonCaptor.capture())
        actionButtonCaptor.thirdValue.invoke()

        verify(onScrollClick, never()).run()
    }

    @Test
    fun scrollChipClicked_afterUpdate_runsNewAction() = runTest {
        actionsProvider = createActionsProvider()

        val onScrollClick = mock<Runnable>()
        val onScrollClick2 = mock<Runnable>()

        actionsProvider.onScrollChipReady(onScrollClick)
        actionsProvider.onScrollChipInvalidated()
        actionsProvider.onScrollChipReady(onScrollClick2)
        val actionButtonCaptor = argumentCaptor<() -> Unit>()
        // share, edit, scroll
        verify(actionsCallback, times(3))
            .provideActionButton(any(), any(), actionButtonCaptor.capture())
        actionButtonCaptor.thirdValue.invoke()

        verify(onScrollClick2).run()
        verify(onScrollClick, never()).run()
    }

    private fun createActionsProvider(): ScreenshotActionsProvider {
        return DefaultScreenshotActionsProvider(
            context,
            uiEventLogger,
            UUID.randomUUID(),
            request,
            actionExecutor,
            actionsCallback,
        )
    }
}
