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

package com.android.systemui.reardisplay

import android.testing.TestableLooper
import android.widget.SeekBar
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.haptics.msdl.msdlPlayer
import com.android.systemui.haptics.slider.HapticSliderPlugin
import com.android.systemui.haptics.vibratorHelper
import com.android.systemui.reardisplay.RearDisplayInnerDialogDelegate.SeekBarListener
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.systemUIDialogDotFactory
import com.android.systemui.testKosmos
import com.android.systemui.util.time.systemClock
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never

/** atest SystemUITests:com.android.systemui.reardisplay.RearDisplayInnerDialogDelegateTest */
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class RearDisplayInnerDialogDelegateTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    @Test
    fun testShowAndDismissDialog() {
        val dialogDelegate =
            RearDisplayInnerDialogDelegate(
                kosmos.systemUIDialogDotFactory,
                mContext,
                kosmos.vibratorHelper,
                kosmos.msdlPlayer,
                kosmos.systemClock,
            ) {}

        val dialog = dialogDelegate.createDialog()
        dialog.show()
        assertTrue(dialog.isShowing)

        dialog.dismiss()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testProgressSlidesToCompletion_callbackInvoked() {
        val mockCallback = mock<Runnable>()
        RearDisplayInnerDialogDelegate(
                kosmos.systemUIDialogDotFactory,
                mContext,
                kosmos.vibratorHelper,
                kosmos.msdlPlayer,
                kosmos.systemClock,
            ) {
                mockCallback.run()
            }
            .createDialog()
            .apply {
                show()
                val seekbar = findViewById<SeekBar>(R.id.seekbar)
                seekbar.progress = 50
                seekbar.progress = 100
                verify(mockCallback).run()
            }
    }

    @Test
    fun testProgressImmediatelyCompletes_callbackNotInvoked() {
        val mockCallback = mock<Runnable>()
        RearDisplayInnerDialogDelegate(
                kosmos.systemUIDialogDotFactory,
                mContext,
                kosmos.vibratorHelper,
                kosmos.msdlPlayer,
                kosmos.systemClock,
            ) {
                mockCallback.run()
            }
            .createDialog()
            .apply {
                show()
                val seekbar = findViewById<SeekBar>(R.id.seekbar)
                seekbar.progress = 100
                verify(mockCallback, never()).run()
            }
    }

    @Test
    fun testProgressResetsWhenStoppingBeforeCompletion() {
        val mockCallback = mock<Runnable>()
        val mockSeekbar = mock<SeekBar>()
        val seekBarListener = SeekBarListener(mock<HapticSliderPlugin>(), mockCallback)

        seekBarListener.onStartTrackingTouch(mockSeekbar)
        seekBarListener.onProgressChanged(mockSeekbar, 50 /* progress */, true /* fromUser */)
        seekBarListener.onStopTrackingTouch(mockSeekbar)

        // Progress is reset
        verify(mockSeekbar).setProgress(eq(0))
    }
}
