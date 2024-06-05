/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.os.Process.myUserHandle
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableContext
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.proxy.SystemUiProxy
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.statusbar.phone.CentralSurfaces
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@RunWith(AndroidTestingRunner::class)
class ActionIntentExecutorTest : SysuiTestCase() {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(mainDispatcher)
    private val testableContext = TestableContext(mContext)

    private val activityManagerWrapper = mock<ActivityManagerWrapper>()
    private val systemUiProxy = mock<SystemUiProxy>()

    private val displayTracker = mock<DisplayTracker>()

    private val actionIntentExecutor =
        ActionIntentExecutor(
            testableContext,
            activityManagerWrapper,
            testScope,
            mainDispatcher,
            systemUiProxy,
            displayTracker,
        )

    @Test
    @EnableFlags(Flags.FLAG_FIX_SCREENSHOT_ACTION_DISMISS_SYSTEM_WINDOWS)
    fun launchIntent_callsCloseSystemWindows() =
        testScope.runTest {
            val intent = Intent(Intent.ACTION_EDIT).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val userHandle = myUserHandle()

            actionIntentExecutor.launchIntent(intent, userHandle, false, null, null)
            scheduler.advanceUntilIdle()

            verify(activityManagerWrapper)
                .closeSystemWindows(CentralSurfaces.SYSTEM_DIALOG_REASON_SCREENSHOT)
        }
}
