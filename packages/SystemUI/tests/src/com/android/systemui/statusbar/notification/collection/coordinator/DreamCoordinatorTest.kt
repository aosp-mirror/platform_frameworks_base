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
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.collection.coordinator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class DreamCoordinatorTest : SysuiTestCase() {
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var notifPipeline: NotifPipeline
    @Mock private lateinit var filterListener: Pluggable.PluggableListener<NotifFilter>

    private val keyguardRepository = FakeKeyguardRepository()
    private var fakeEntry: NotificationEntry = NotificationEntryBuilder().build()
    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = TestScope(testDispatcher)

    private lateinit var filter: NotifFilter
    private lateinit var statusBarListener: StatusBarStateController.StateListener
    private lateinit var dreamCoordinator: DreamCoordinator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        // Build the coordinator
        dreamCoordinator =
            DreamCoordinator(
                statusBarStateController,
                testScope.backgroundScope,
                keyguardRepository
            )

        // Attach the pipeline and capture the listeners/filters that it registers
        dreamCoordinator.attach(notifPipeline)

        filter = withArgCaptor { verify(notifPipeline).addPreGroupFilter(capture()) }
        filter.setInvalidationListener(filterListener)

        statusBarListener = withArgCaptor {
            verify(statusBarStateController).addCallback(capture())
        }
    }

    @Test
    fun hideNotifications_whenDreamingAndOnKeyguard() =
        testScope.runTest {
            // GIVEN we are on keyguard and not dreaming
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setIsActiveDreamLockscreenHosted(false)
            runCurrent()

            // THEN notifications are not filtered out
            verifyPipelinesNotInvalidated()
            assertThat(filter.shouldFilterOut(fakeEntry, 0L)).isFalse()

            // WHEN dreaming starts and the active dream is hosted in lockscreen
            keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            runCurrent()

            // THEN pipeline is notified and notifications should all be filtered out
            verifyPipelinesInvalidated()
            assertThat(filter.shouldFilterOut(fakeEntry, 0L)).isTrue()
        }

    @Test
    fun showNotifications_whenDreamingAndNotOnKeyguard() =
        testScope.runTest {
            // GIVEN we are on the keyguard and active dream is hosted in lockscreen
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            runCurrent()

            // THEN pipeline is notified and notifications are all filtered out
            verifyPipelinesInvalidated()
            clearPipelineInvocations()
            assertThat(filter.shouldFilterOut(fakeEntry, 0L)).isTrue()

            // WHEN we are no longer on the keyguard
            statusBarListener.onStateChanged(StatusBarState.SHADE)

            // THEN pipeline is notified and notifications are not filtered out
            verifyPipelinesInvalidated()
            assertThat(filter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }

    @Test
    fun showNotifications_whenOnKeyguardAndNotDreaming() =
        testScope.runTest {
            // GIVEN we are on the keyguard and active dream is hosted in lockscreen
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            runCurrent()

            // THEN pipeline is notified and notifications are all filtered out
            verifyPipelinesInvalidated()
            clearPipelineInvocations()
            assertThat(filter.shouldFilterOut(fakeEntry, 0L)).isTrue()

            // WHEN the lockscreen hosted dream stops
            keyguardRepository.setIsActiveDreamLockscreenHosted(false)
            runCurrent()

            // THEN pipeline is notified and notifications are not filtered out
            verifyPipelinesInvalidated()
            assertThat(filter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }

    private fun verifyPipelinesInvalidated() {
        verify(filterListener).onPluggableInvalidated(eq(filter), any())
    }

    private fun verifyPipelinesNotInvalidated() {
        verify(filterListener, never()).onPluggableInvalidated(eq(filter), any())
    }

    private fun clearPipelineInvocations() {
        clearInvocations(filterListener)
    }
}
