/*
 * Copyright (C) 2022 The Android Open Source Project
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
import androidx.test.filters.SmallTest
import com.android.keyguard.LockIconView.ICON_LOCK
import com.android.systemui.doze.util.getBurnInOffset
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LockIconViewControllerWithCoroutinesTest : LockIconViewControllerBaseTest() {

    /** After migration, replaces LockIconViewControllerTest version */
    @Test
    fun testLockIcon_clearsIconOnAod_whenUdfpsNotEnrolled() =
        runBlocking(IMMEDIATE) {
            // GIVEN udfps not enrolled
            setupUdfps()
            whenever(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(false)

            // GIVEN starting state for the lock icon
            setupShowLockIcon()

            // GIVEN lock icon controller is initialized and view is attached
            init(/* useMigrationFlag= */ true)
            reset(mLockIconView)

            // WHEN the dozing state changes
            mUnderTest.mIsDozingCallback.accept(true)

            // THEN the icon is cleared
            verify(mLockIconView).clearIcon()
        }

    /** After migration, replaces LockIconViewControllerTest version */
    @Test
    fun testLockIcon_updateToAodLock_whenUdfpsEnrolled() =
        runBlocking(IMMEDIATE) {
            // GIVEN udfps enrolled
            setupUdfps()
            whenever(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(true)

            // GIVEN starting state for the lock icon
            setupShowLockIcon()

            // GIVEN lock icon controller is initialized and view is attached
            init(/* useMigrationFlag= */ true)
            reset(mLockIconView)

            // WHEN the dozing state changes
            mUnderTest.mIsDozingCallback.accept(true)

            // THEN the AOD lock icon should show
            verify(mLockIconView).updateIcon(ICON_LOCK, true)
        }

    /** After migration, replaces LockIconViewControllerTest version */
    @Test
    fun testBurnInOffsetsUpdated_onDozeAmountChanged() =
        runBlocking(IMMEDIATE) {
            // GIVEN udfps enrolled
            setupUdfps()
            whenever(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(true)

            // GIVEN burn-in offset = 5
            val burnInOffset = 5
            whenever(getBurnInOffset(anyInt(), anyBoolean())).thenReturn(burnInOffset)

            // GIVEN starting state for the lock icon (keyguard)
            setupShowLockIcon()
            init(/* useMigrationFlag= */ true)
            reset(mLockIconView)

            // WHEN dozing updates
            mUnderTest.mIsDozingCallback.accept(true)
            mUnderTest.mDozeTransitionCallback.accept(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))

            // THEN the view's translation is updated to use the AoD burn-in offsets
            verify(mLockIconView).setTranslationY(burnInOffset.toFloat())
            verify(mLockIconView).setTranslationX(burnInOffset.toFloat())
            reset(mLockIconView)

            // WHEN the device is no longer dozing
            mUnderTest.mIsDozingCallback.accept(false)
            mUnderTest.mDozeTransitionCallback.accept(TransitionStep(AOD, LOCKSCREEN, 0f, FINISHED))

            // THEN the view is updated to NO translation (no burn-in offsets anymore)
            verify(mLockIconView).setTranslationY(0f)
            verify(mLockIconView).setTranslationX(0f)
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
