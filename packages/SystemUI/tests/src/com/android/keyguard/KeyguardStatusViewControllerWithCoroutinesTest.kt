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

package com.android.keyguard

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.power.shared.model.ScreenPowerState
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class KeyguardStatusViewControllerWithCoroutinesTest : KeyguardStatusViewControllerBaseTest() {

    @Test
    fun dozeTimeTickUpdatesSlices() = runTest {
        mController.startCoroutines(coroutineContext)
        givenViewAttached()
        runCurrent()
        clearInvocations(mKeyguardSliceViewController)

        mFakeKeyguardRepository.dozeTimeTick()
        runCurrent()
        verify(mKeyguardSliceViewController).refresh()

        coroutineContext.cancelChildren()
    }

    @Test
    fun onScreenTurningOnUpdatesSlices() = runTest {
        mController.startCoroutines(coroutineContext)
        givenViewAttached()
        runCurrent()
        clearInvocations(mKeyguardSliceViewController)

        mFakePowerRepository.setScreenPowerState(ScreenPowerState.SCREEN_ON)
        runCurrent()
        verify(mKeyguardSliceViewController, never()).refresh()

        // Should only be called during a 'turning on' event
        mFakePowerRepository.setScreenPowerState(ScreenPowerState.SCREEN_TURNING_ON)
        runCurrent()
        verify(mKeyguardSliceViewController).refresh()

        coroutineContext.cancelChildren()
    }
}
