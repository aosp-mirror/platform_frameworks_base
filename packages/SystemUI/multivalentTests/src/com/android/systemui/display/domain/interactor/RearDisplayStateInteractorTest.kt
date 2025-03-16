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

package com.android.systemui.display.domain.interactor

import android.hardware.display.defaultDisplay
import android.hardware.display.rearDisplay
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.FakeDeviceStateRepository
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

/** atest RearDisplayStateInteractorTest */
@RunWith(AndroidJUnit4::class)
@SmallTest
class RearDisplayStateInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val fakeDisplayRepository = FakeDisplayRepository()
    private val fakeDeviceStateRepository = FakeDeviceStateRepository()
    private val rearDisplayStateInteractor =
        RearDisplayStateInteractorImpl(
            fakeDisplayRepository,
            fakeDeviceStateRepository,
            kosmos.testDispatcher,
        )
    private val emissionTracker = EmissionTracker(rearDisplayStateInteractor, kosmos.testScope)

    @Before
    fun setup() {
        whenever(kosmos.rearDisplay.flags).thenReturn(Display.FLAG_REAR)
    }

    @Test
    fun enableRearDisplayWhenDisplayImmediatelyAvailable() =
        kosmos.runTest {
            emissionTracker.use { tracker ->
                fakeDisplayRepository.addDisplay(kosmos.rearDisplay)
                assertThat(tracker.enabledCount).isEqualTo(0)
                fakeDeviceStateRepository.emit(
                    DeviceStateRepository.DeviceState.REAR_DISPLAY_OUTER_DEFAULT
                )

                assertThat(tracker.enabledCount).isEqualTo(1)
                assertThat(tracker.lastDisplay).isEqualTo(kosmos.rearDisplay)
            }
        }

    @Test
    fun enableAndDisableRearDisplay() =
        kosmos.runTest {
            emissionTracker.use { tracker ->
                // The fake FakeDeviceStateRepository will always start with state UNKNOWN, thus
                // triggering one initial emission
                assertThat(tracker.disabledCount).isEqualTo(1)

                fakeDeviceStateRepository.emit(
                    DeviceStateRepository.DeviceState.REAR_DISPLAY_OUTER_DEFAULT
                )

                // Adding a non-rear display does not trigger an emission
                fakeDisplayRepository.addDisplay(kosmos.defaultDisplay)
                assertThat(tracker.enabledCount).isEqualTo(0)

                // Adding a rear display triggers the emission
                fakeDisplayRepository.addDisplay(kosmos.rearDisplay)
                assertThat(tracker.enabledCount).isEqualTo(1)
                assertThat(tracker.lastDisplay).isEqualTo(kosmos.rearDisplay)

                fakeDeviceStateRepository.emit(DeviceStateRepository.DeviceState.UNFOLDED)
                assertThat(tracker.disabledCount).isEqualTo(2)
            }
        }

    @Test
    fun enableRearDisplayShouldOnlyReactToFirstRearDisplay() =
        kosmos.runTest {
            emissionTracker.use { tracker ->
                fakeDeviceStateRepository.emit(
                    DeviceStateRepository.DeviceState.REAR_DISPLAY_OUTER_DEFAULT
                )

                // Adding a rear display triggers the emission
                fakeDisplayRepository.addDisplay(kosmos.rearDisplay)
                assertThat(tracker.enabledCount).isEqualTo(1)

                // Adding additional rear displays does not trigger additional emissions
                fakeDisplayRepository.addDisplay(kosmos.rearDisplay)
                assertThat(tracker.enabledCount).isEqualTo(1)
            }
        }

    @Test
    fun rearDisplayAddedWhenNoLongerInRdm() =
        kosmos.runTest {
            emissionTracker.use { tracker ->
                fakeDeviceStateRepository.emit(
                    DeviceStateRepository.DeviceState.REAR_DISPLAY_OUTER_DEFAULT
                )
                fakeDeviceStateRepository.emit(DeviceStateRepository.DeviceState.UNFOLDED)

                // Adding a rear display when no longer in the correct device state does not trigger
                // an emission
                fakeDisplayRepository.addDisplay(kosmos.rearDisplay)
                assertThat(tracker.enabledCount).isEqualTo(0)
            }
        }

    @Test
    fun rearDisplayDisabledDoesNotSpam() =
        kosmos.runTest {
            emissionTracker.use { tracker ->
                fakeDeviceStateRepository.emit(DeviceStateRepository.DeviceState.UNFOLDED)
                assertThat(tracker.disabledCount).isEqualTo(1)

                // No additional emission
                fakeDeviceStateRepository.emit(DeviceStateRepository.DeviceState.FOLDED)
                assertThat(tracker.disabledCount).isEqualTo(1)
            }
        }

    class EmissionTracker(rearDisplayInteractor: RearDisplayStateInteractor, scope: TestScope) :
        AutoCloseable {
        var enabledCount = 0
        var disabledCount = 0
        var lastDisplay: Display? = null

        val job: Job

        init {
            val channel = Channel<RearDisplayStateInteractor.State>(Channel.UNLIMITED)
            job =
                scope.launch {
                    rearDisplayInteractor.state.collect {
                        channel.send(it)
                        if (it is RearDisplayStateInteractor.State.Enabled) {
                            enabledCount++
                            lastDisplay = it.innerDisplay
                        }
                        if (it is RearDisplayStateInteractor.State.Disabled) {
                            disabledCount++
                        }
                    }
                }
        }

        override fun close() {
            job.cancel()
        }
    }
}
