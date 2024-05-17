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

package com.android.systemui.statusbar

import android.testing.TestableLooper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class KeyguardIndicationControllerWithCoroutinesTest : KeyguardIndicationControllerBaseTest() {
    @Test
    fun testIndicationAreaVisibility_onLockscreenHostedDreamStateChanged() =
        runBlocking(IMMEDIATE) {
            // GIVEN starting state for keyguard indication and wallpaper dream enabled
            createController()
            mFlags.set(LOCKSCREEN_WALLPAPER_DREAM_ENABLED, true)
            mController.setVisible(true)

            // THEN indication area is visible
            verify(mIndicationArea, times(2)).visibility = View.VISIBLE

            // WHEN the device is dreaming with lockscreen hosted dream
            mController.mIsActiveDreamLockscreenHostedCallback.accept(
                true /* isActiveDreamLockscreenHosted */
            )
            mExecutor.runAllReady()

            // THEN the indication area is hidden
            verify(mIndicationArea).visibility = View.GONE

            // WHEN the device stops dreaming with lockscreen hosted dream
            mController.mIsActiveDreamLockscreenHostedCallback.accept(
                false /* isActiveDreamLockscreenHosted */
            )
            mExecutor.runAllReady()

            // THEN indication area is set visible
            verify(mIndicationArea, times(3)).visibility = View.VISIBLE
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
