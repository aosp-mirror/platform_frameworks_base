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

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class KeyguardClockSwitchControllerWithCoroutinesTest : KeyguardClockSwitchControllerBaseTest() {

    @Test
    fun testStatusAreaVisibility_onLockscreenHostedDreamStateChanged() =
        runBlocking(IMMEDIATE) {
            // GIVEN starting state for the keyguard clock and wallpaper dream enabled
            mFakeFeatureFlags.set(LOCKSCREEN_WALLPAPER_DREAM_ENABLED, true)
            init()

            // WHEN dreaming starts
            mController.mIsActiveDreamLockscreenHostedCallback.accept(
                true /* isActiveDreamLockscreenHosted */
            )

            // THEN the status area is hidden
            mExecutor.runAllReady()
            assertEquals(View.INVISIBLE, mStatusArea.visibility)

            // WHEN dreaming stops
            mController.mIsActiveDreamLockscreenHostedCallback.accept(
                false /* isActiveDreamLockscreenHosted */
            )
            mExecutor.runAllReady()

            // THEN status area view is visible
            assertEquals(View.VISIBLE, mStatusArea.visibility)
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
