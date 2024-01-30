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
package com.android.systemui.statusbar.events

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class SystemEventCoordinatorTest : SysuiTestCase() {

    private val fakeSystemClock = FakeSystemClock()
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val connectedDisplayInteractor = FakeConnectedDisplayInteractor()

    @Mock lateinit var batteryController: BatteryController
    @Mock lateinit var privacyController: PrivacyItemController
    @Mock lateinit var scheduler: SystemStatusAnimationScheduler

    private lateinit var systemEventCoordinator: SystemEventCoordinator
    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        systemEventCoordinator =
            SystemEventCoordinator(
                    fakeSystemClock,
                    batteryController,
                    privacyController,
                    context,
                    TestScope(UnconfinedTestDispatcher()),
                    connectedDisplayInteractor
                )
                .apply { attachScheduler(scheduler) }
    }

    @Test
    fun startObserving_propagatesConnectedDisplayStatusEvents() =
        testScope.runTest {
            systemEventCoordinator.startObserving()

            connectedDisplayInteractor.emit()
            connectedDisplayInteractor.emit()

            verify(scheduler, times(2)).onStatusEvent(any<ConnectedDisplayEvent>())
        }

    @Test
    fun stopObserving_doesNotPropagateConnectedDisplayStatusEvents() =
        testScope.runTest {
            systemEventCoordinator.startObserving()

            connectedDisplayInteractor.emit()

            verify(scheduler).onStatusEvent(any<ConnectedDisplayEvent>())

            systemEventCoordinator.stopObserving()

            connectedDisplayInteractor.emit()

            verifyNoMoreInteractions(scheduler)
        }

    class FakeConnectedDisplayInteractor : ConnectedDisplayInteractor {
        private val flow = MutableSharedFlow<Unit>()
        suspend fun emit() = flow.emit(Unit)
        override val connectedDisplayState: Flow<ConnectedDisplayInteractor.State>
            get() = MutableSharedFlow<ConnectedDisplayInteractor.State>()
        override val connectedDisplayAddition: Flow<Unit>
            get() = flow
        override val pendingDisplay: Flow<PendingDisplay?>
            get() = MutableSharedFlow<PendingDisplay>()
        override val concurrentDisplaysInProgress: Flow<Boolean>
            get() = TODO("Not yet implemented")
    }
}
