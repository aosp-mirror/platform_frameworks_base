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

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.view.Window
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ActionExecutorTest : SysuiTestCase() {
    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(mainDispatcher)

    private val intentExecutor = mock<ActionIntentExecutor>()
    private val window = mock<Window>()
    private val viewProxy = mock<ScreenshotShelfViewProxy>()
    private val onDismiss = mock<(() -> Unit)>()
    private val pendingIntent = mock<PendingIntent>()

    private lateinit var actionExecutor: ActionExecutor

    @Test
    fun startSharedTransition_callsLaunchIntent() = runTest {
        actionExecutor = createActionExecutor()

        actionExecutor.startSharedTransition(Intent(Intent.ACTION_EDIT), UserHandle.CURRENT, true)
        scheduler.advanceUntilIdle()

        val intentCaptor = argumentCaptor<Intent>()
        verifyBlocking(intentExecutor) {
            launchIntent(capture(intentCaptor), eq(UserHandle.CURRENT), eq(true), any(), any())
        }
        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_EDIT)
    }

    @Test
    fun sendPendingIntent_requestsDismissal() = runTest {
        actionExecutor = createActionExecutor()

        actionExecutor.sendPendingIntent(pendingIntent)

        verify(pendingIntent).send(any(Bundle::class.java))
        verify(viewProxy).requestDismissal(null)
    }

    private fun createActionExecutor(): ActionExecutor {
        return ActionExecutor(intentExecutor, testScope, window, viewProxy, onDismiss)
    }
}
