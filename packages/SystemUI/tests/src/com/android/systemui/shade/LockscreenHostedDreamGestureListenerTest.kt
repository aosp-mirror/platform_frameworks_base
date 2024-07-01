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

package com.android.systemui.shade

import android.os.PowerManager
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LockscreenHostedDreamGestureListenerTest : SysuiTestCase() {
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var shadeLogger: ShadeLogger
    @Mock private lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var powerRepository: FakePowerRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var underTest: LockscreenHostedDreamGestureListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        powerRepository = FakePowerRepository()
        keyguardRepository = FakeKeyguardRepository()

        underTest =
            LockscreenHostedDreamGestureListener(
                falsingManager,
                PowerInteractorFactory.create(
                        repository = powerRepository,
                        statusBarStateController = statusBarStateController,
                    )
                    .powerInteractor,
                statusBarStateController,
                primaryBouncerInteractor,
                keyguardRepository,
                shadeLogger,
            )
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(primaryBouncerInteractor.isBouncerShowing()).thenReturn(false)
    }

    @Test
    fun testGestureDetector_onSingleTap_whileDreaming() =
        testScope.runTest {
            // GIVEN device dreaming and the dream is hosted in lockscreen
            whenever(statusBarStateController.isDreaming).thenReturn(true)
            keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            testScope.runCurrent()

            // GIVEN the falsing manager does NOT think the tap is a false tap
            whenever(falsingManager.isFalseTap(ArgumentMatchers.anyInt())).thenReturn(false)

            // WHEN there's a tap
            underTest.onSingleTapUp(upEv)

            // THEN wake up device if dreaming
            Truth.assertThat(powerRepository.lastWakeWhy).isNotNull()
            Truth.assertThat(powerRepository.lastWakeReason).isEqualTo(PowerManager.WAKE_REASON_TAP)
        }

    @Test
    fun testGestureDetector_onSingleTap_notOnKeyguard() =
        testScope.runTest {
            // GIVEN device dreaming and the dream is hosted in lockscreen
            whenever(statusBarStateController.isDreaming).thenReturn(true)
            keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            testScope.runCurrent()

            // GIVEN shade is open
            whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)

            // GIVEN the falsing manager does NOT think the tap is a false tap
            whenever(falsingManager.isFalseTap(ArgumentMatchers.anyInt())).thenReturn(false)

            // WHEN there's a tap
            underTest.onSingleTapUp(upEv)

            // THEN the falsing manager never gets a call
            verify(falsingManager, never()).isFalseTap(ArgumentMatchers.anyInt())
        }

    @Test
    fun testGestureDetector_onSingleTap_bouncerShown() =
        testScope.runTest {
            // GIVEN device dreaming and the dream is hosted in lockscreen
            whenever(statusBarStateController.isDreaming).thenReturn(true)
            keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            testScope.runCurrent()

            // GIVEN bouncer is expanded
            whenever(primaryBouncerInteractor.isBouncerShowing()).thenReturn(true)

            // GIVEN the falsing manager does NOT think the tap is a false tap
            whenever(falsingManager.isFalseTap(ArgumentMatchers.anyInt())).thenReturn(false)

            // WHEN there's a tap
            underTest.onSingleTapUp(upEv)

            // THEN the falsing manager never gets a call
            verify(falsingManager, never()).isFalseTap(ArgumentMatchers.anyInt())
        }

    @Test
    fun testGestureDetector_onSingleTap_falsing() =
        testScope.runTest {
            // GIVEN device dreaming and the dream is hosted in lockscreen
            whenever(statusBarStateController.isDreaming).thenReturn(true)
            keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            testScope.runCurrent()

            // GIVEN the falsing manager thinks the tap is a false tap
            whenever(falsingManager.isFalseTap(ArgumentMatchers.anyInt())).thenReturn(true)

            // WHEN there's a tap
            underTest.onSingleTapUp(upEv)

            // THEN the device doesn't wake up
            Truth.assertThat(powerRepository.lastWakeWhy).isNull()
            Truth.assertThat(powerRepository.lastWakeReason).isNull()
        }

    @Test
    fun testSingleTap_notDreaming_noFalsingCheck() =
        testScope.runTest {
            // GIVEN device not dreaming with lockscreen hosted dream
            whenever(statusBarStateController.isDreaming).thenReturn(false)
            keyguardRepository.setIsActiveDreamLockscreenHosted(false)
            testScope.runCurrent()

            // WHEN there's a tap
            underTest.onSingleTapUp(upEv)

            // THEN the falsing manager never gets a call
            verify(falsingManager, never()).isFalseTap(ArgumentMatchers.anyInt())
        }
}

private val upEv = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
