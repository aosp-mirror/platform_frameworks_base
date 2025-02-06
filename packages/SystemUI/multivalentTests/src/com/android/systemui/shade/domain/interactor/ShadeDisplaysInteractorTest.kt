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

package com.android.systemui.shade.domain.interactor

import android.content.res.Configuration
import android.content.res.mockResources
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.configurationRepository
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.scene.ui.view.mockShadeRootView
import com.android.systemui.shade.data.repository.fakeShadeDisplaysRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.statusbar.notification.row.notificationRebindingTracker
import com.android.systemui.statusbar.notification.stack.notificationStackRebindingHider
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class ShadeDisplaysInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val testScope = kosmos.testScope
    private val shadeRootview = kosmos.mockShadeRootView
    private val positionRepository = kosmos.fakeShadeDisplaysRepository
    private val shadeContext = kosmos.mockedWindowContext
    private val resources = kosmos.mockResources
    private val latencyTracker = kosmos.mockedShadeDisplayChangeLatencyTracker
    private val configuration = mock<Configuration>()
    private val display = mock<Display>()
    private val activeNotificationRepository = kosmos.activeNotificationListRepository
    private val notificationRebindingTracker = kosmos.notificationRebindingTracker
    private val notificationStackRebindingHider = kosmos.notificationStackRebindingHider
    private val configurationRepository = kosmos.fakeConfigurationRepository

    private val underTest by lazy { kosmos.shadeDisplaysInteractor }

    @Before
    fun setup() {
        whenever(shadeRootview.display).thenReturn(display)
        whenever(display.displayId).thenReturn(0)

        whenever(resources.configuration).thenReturn(configuration)

        whenever(shadeContext.displayId).thenReturn(0)
        whenever(shadeContext.resources).thenReturn(resources)
        whenever(shadeContext.display).thenReturn(display)
    }

    @Test
    fun start_shadeInCorrectPosition_notAddedOrRemoved() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(0)

            underTest.start()

            verify(shadeContext, never()).reparentToDisplay(any())
        }

    @Test
    fun start_shadeInWrongPosition_changes() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(1)

            underTest.start()

            verify(shadeContext).reparentToDisplay(eq(1))
        }

    @Test
    fun start_shadeInWrongPosition_logsStartToLatencyTracker() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(1)

            underTest.start()

            verify(latencyTracker).onShadeDisplayChanging(eq(1))
        }

    @Test
    fun start_shadeInWrongPosition_someNotificationsVisible_hiddenThenShown() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(1)
            activeNotificationRepository.setActiveNotifs(1)

            underTest.start()

            verify(notificationStackRebindingHider).setVisible(eq(false), eq(false))
            configurationRepository.onMovedToDisplay(1)
            verify(notificationStackRebindingHider).setVisible(eq(true), eq(true))
        }

    @Test
    fun start_shadeInWrongPosition_someNotificationsVisible_waitsForInflationsBeforeShowingNssl() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(1)
            activeNotificationRepository.setActiveNotifs(1)

            val endRebinding = notificationRebindingTracker.trackRebinding("test")

            assertThat(notificationRebindingTracker.rebindingInProgressCount.value).isEqualTo(1)

            underTest.start()

            verify(notificationStackRebindingHider).setVisible(eq(false), eq(false))
            configurationRepository.onMovedToDisplay(1)

            // Verify that setVisible(true, true) is NOT called yet, as we
            // first need to wait for notification bindings to have happened
            verify(notificationStackRebindingHider, never()).setVisible(eq(true), eq(true))

            endRebinding.onFinished()

            // Now verify that setVisible(true, true) is called
            verify(notificationStackRebindingHider, times(1)).setVisible(eq(true), eq(true))
        }

    @Test
    fun start_shadeInWrongPosition_noNotifications_nsslNotHidden() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(1)
            activeNotificationRepository.setActiveNotifs(0)

            underTest.start()

            verify(notificationStackRebindingHider)
                .setVisible(visible = eq(true), animated = eq(false))
            verify(notificationStackRebindingHider, never()).setVisible(eq(false), eq(false))
            verify(notificationStackRebindingHider, never()).setVisible(eq(true), eq(true))
        }

    @Test
    fun start_shadeInWrongPosition_waitsUntilMovedToDisplayReceived() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(1)
            activeNotificationRepository.setActiveNotifs(1)

            underTest.start()

            verify(notificationStackRebindingHider).setVisible(eq(false), eq(false))
            // It's not set to visible yet, as we first need to wait for the view to receive the
            // display moved callback.
            verify(notificationStackRebindingHider, never()).setVisible(eq(true), eq(true))

            configurationRepository.onMovedToDisplay(1)

            verify(notificationStackRebindingHider).setVisible(eq(true), eq(true))
        }

    @Test
    fun start_registersConfigChangeListener() {
        underTest.start()

        verify(shadeContext).registerComponentCallbacks(any())
    }
}
