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

package com.android.systemui.keyguard.domain.interactor

import android.app.AlarmManager
import android.app.admin.alarmManager
import android.app.admin.devicePolicyManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.mockedContext
import android.os.PowerManager
import android.os.UserHandle
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.lockPatternUtils
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeSettings
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardWakeDirectlyToGoneInteractorTest : SysuiTestCase() {

    private var lastRegisteredBroadcastReceiver: BroadcastReceiver? = null
    private val kosmos =
        testKosmos().apply {
            whenever(mockedContext.user).thenReturn(mock<UserHandle>())
            doAnswer { invocation ->
                    lastRegisteredBroadcastReceiver = invocation.arguments[0] as BroadcastReceiver
                }
                .whenever(mockedContext)
                .registerReceiver(any(), any(), any(), any(), any())
        }

    private val testScope = kosmos.testScope
    private val underTest = kosmos.keyguardWakeDirectlyToGoneInteractor
    private val lockPatternUtils = kosmos.lockPatternUtils
    private val repository = kosmos.fakeKeyguardRepository
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testCanWakeDirectlyToGone_keyguardServiceEnabledThenDisabled() =
        testScope.runTest {
            val canWake by collectValues(underTest.canWakeDirectlyToGone)

            assertEquals(
                listOf(
                    false, // Defaults to false.
                ),
                canWake
            )

            repository.setKeyguardEnabled(false)
            runCurrent()

            assertEquals(
                listOf(
                    false, // Default to false.
                    true, // True now that keyguard service is disabled
                ),
                canWake
            )

            repository.setKeyguardEnabled(true)
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                    false,
                ),
                canWake
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testCanWakeDirectlyToGone_lockscreenDisabledThenEnabled() =
        testScope.runTest {
            val canWake by collectValues(underTest.canWakeDirectlyToGone)

            assertEquals(
                listOf(
                    false, // Defaults to false.
                ),
                canWake
            )

            whenever(lockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(true)
            runCurrent()

            assertEquals(
                listOf(
                    // Still false - isLockScreenDisabled only causes canWakeDirectlyToGone to
                    // update on the next wake/sleep event.
                    false,
                ),
                canWake
            )

            kosmos.powerInteractor.setAsleepForTest()
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    // True since we slept after setting isLockScreenDisabled=true
                    true,
                ),
                canWake
            )

            kosmos.powerInteractor.setAwakeForTest()
            runCurrent()

            kosmos.powerInteractor.setAsleepForTest()
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                ),
                canWake
            )

            whenever(lockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false)
            kosmos.powerInteractor.setAwakeForTest()
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                    false,
                ),
                canWake
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testCanWakeDirectlyToGone_wakeAndUnlock() =
        testScope.runTest {
            val canWake by collectValues(underTest.canWakeDirectlyToGone)

            assertEquals(
                listOf(
                    false, // Defaults to false.
                ),
                canWake
            )

            repository.setBiometricUnlockState(BiometricUnlockMode.WAKE_AND_UNLOCK)
            runCurrent()

            assertEquals(listOf(false, true), canWake)

            repository.setBiometricUnlockState(BiometricUnlockMode.NONE)
            runCurrent()

            assertEquals(listOf(false, true, false), canWake)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testCanWakeDirectlyToGone_andSetsAlarm_ifPowerButtonDoesNotLockImmediately() =
        testScope.runTest {
            val canWake by collectValues(underTest.canWakeDirectlyToGone)

            assertEquals(
                listOf(
                    false, // Defaults to false.
                ),
                canWake
            )

            repository.setCanIgnoreAuthAndReturnToGone(true)
            runCurrent()

            assertEquals(listOf(false, true), canWake)

            repository.setCanIgnoreAuthAndReturnToGone(false)
            runCurrent()

            assertEquals(listOf(false, true, false), canWake)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testSetsCanIgnoreAuth_andSetsAlarm_whenTimingOut() =
        testScope.runTest {
            val canWake by collectValues(underTest.canWakeDirectlyToGone)

            assertEquals(
                listOf(
                    false, // Defaults to false.
                ),
                canWake
            )

            whenever(kosmos.devicePolicyManager.getMaximumTimeToLock(eq(null), anyInt()))
                .thenReturn(-1)
            kosmos.fakeSettings.putInt(Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT, 500)

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )

            kosmos.powerInteractor.setAsleepForTest(
                sleepReason = PowerManager.GO_TO_SLEEP_REASON_TIMEOUT
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                ),
                canWake
            )

            verify(kosmos.alarmManager)
                .setExactAndAllowWhileIdle(
                    eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                    anyLong(),
                    any(),
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testCancelsFirstAlarm_onWake_withSecondAlarmSet() =
        testScope.runTest {
            val canWake by collectValues(underTest.canWakeDirectlyToGone)

            assertEquals(
                listOf(
                    false, // Defaults to false.
                ),
                canWake
            )

            whenever(kosmos.devicePolicyManager.getMaximumTimeToLock(eq(null), anyInt()))
                .thenReturn(-1)
            kosmos.fakeSettings.putInt(Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT, 500)

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )

            kosmos.powerInteractor.setAsleepForTest(
                sleepReason = PowerManager.GO_TO_SLEEP_REASON_TIMEOUT
            )
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    // Timed out, so we can ignore auth/return to GONE.
                    true,
                ),
                canWake
            )

            verify(kosmos.alarmManager)
                .setExactAndAllowWhileIdle(
                    eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                    anyLong(),
                    any(),
                )

            kosmos.powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope = testScope,
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                    // Should be canceled by the wakeup, but there would still be an
                    // alarm in flight that should be canceled.
                    false,
                ),
                canWake
            )

            kosmos.powerInteractor.setAsleepForTest(
                sleepReason = PowerManager.GO_TO_SLEEP_REASON_TIMEOUT
            )
            runCurrent()

            assertEquals(
                listOf(
                    false,
                    true,
                    false,
                    // Back to sleep.
                    true,
                ),
                canWake
            )

            // Simulate the first sleep's alarm coming in.
            lastRegisteredBroadcastReceiver?.onReceive(
                kosmos.mockedContext,
                Intent("com.android.internal.policy.impl.PhoneWindowManager.DELAYED_KEYGUARD")
            )
            runCurrent()

            // It should not have any effect.
            assertEquals(
                listOf(
                    false,
                    true,
                    false,
                    true,
                ),
                canWake
            )
        }
}
