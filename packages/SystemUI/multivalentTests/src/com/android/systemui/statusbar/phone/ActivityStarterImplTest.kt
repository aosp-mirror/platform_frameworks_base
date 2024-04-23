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

package com.android.systemui.statusbar.phone

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ActivityStarterImplTest : SysuiTestCase() {
    @Mock private lateinit var legacyActivityStarterInternal: LegacyActivityStarterInternalImpl
    @Mock private lateinit var activityStarterInternal: ActivityStarterInternalImpl
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    private lateinit var underTest: ActivityStarterImpl
    private val mainExecutor = FakeExecutor(FakeSystemClock())

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            ActivityStarterImpl(
                statusBarStateController = statusBarStateController,
                mainExecutor = mainExecutor,
                legacyActivityStarter = { legacyActivityStarterInternal },
            )
    }

    @Test
    fun postStartActivityDismissingKeyguard_pendingIntent_postsOnMain() {
        val intent = mock(PendingIntent::class.java)

        underTest.postStartActivityDismissingKeyguard(intent)

        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun postStartActivityDismissingKeyguard_intent_postsOnMain() {
        underTest.postStartActivityDismissingKeyguard(mock(Intent::class.java), 0)

        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun postQSRunnableDismissingKeyguard_leaveOpenStatusBarState() {
        underTest.postQSRunnableDismissingKeyguard {}

        assertThat(mainExecutor.numPending()).isEqualTo(1)
        mainExecutor.runAllReady()
        verify(statusBarStateController).setLeaveOpenOnKeyguardHide(true)
    }
}
