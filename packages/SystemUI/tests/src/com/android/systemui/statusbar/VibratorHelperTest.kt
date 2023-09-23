package com.android.systemui.statusbar

import android.media.AudioAttributes
import android.os.UserHandle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.testing.AndroidTestingRunner
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import java.util.concurrent.Executor

@RunWith(AndroidTestingRunner::class)
@SmallTest
class VibratorHelperTest : SysuiTestCase() {

    @JvmField @Rule
    var rule = MockitoJUnit.rule()

    @Mock lateinit var vibrator: Vibrator
    @Mock lateinit var executor: Executor
    @Mock lateinit var view: View
    @Captor lateinit var backgroundTaskCaptor: ArgumentCaptor<Runnable>
    lateinit var vibratorHelper: VibratorHelper

    @Before
    fun setup() {
        vibratorHelper = VibratorHelper(vibrator, executor)
        whenever(vibrator.hasVibrator()).thenReturn(true)
    }

    @Test
    fun testVibrate() {
        vibratorHelper.vibrate(VibrationEffect.EFFECT_CLICK)
        verifyAsync().vibrate(any(VibrationEffect::class.java),
                any(VibrationAttributes::class.java))
    }

    @Test
    fun testVibrate2() {
        vibratorHelper.vibrate(UserHandle.USER_CURRENT, "package",
                mock(VibrationEffect::class.java), "reason",
                mock(VibrationAttributes::class.java))
        verifyAsync().vibrate(eq(UserHandle.USER_CURRENT), eq("package"),
                any(VibrationEffect::class.java), eq("reason"),
                any(VibrationAttributes::class.java))
    }

    @Test
    fun testVibrate3() {
        vibratorHelper.vibrate(mock(VibrationEffect::class.java), mock(AudioAttributes::class.java))
        verifyAsync().vibrate(any(VibrationEffect::class.java), any(AudioAttributes::class.java))
    }

    @Test
    fun testVibrate4() {
        vibratorHelper.vibrate(mock(VibrationEffect::class.java))
        verifyAsync().vibrate(any(VibrationEffect::class.java))
    }

    @Test
    fun testVibrate5() {
        vibratorHelper.vibrate(
            mock(VibrationEffect::class.java),
            mock(VibrationAttributes::class.java)
        )
        verifyAsync().vibrate(
            any(VibrationEffect::class.java),
            any(VibrationAttributes::class.java)
        )
    }

    @Test
    fun testPerformHapticFeedback() {
        val constant = HapticFeedbackConstants.CONFIRM
        vibratorHelper.performHapticFeedback(view, constant)
        verify(view).performHapticFeedback(eq(constant))
    }

    @Test
    fun testPerformHapticFeedback_withFlags() {
        val constant = HapticFeedbackConstants.CONFIRM
        val flag = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        vibratorHelper.performHapticFeedback(view, constant, flag)
        verify(view).performHapticFeedback(eq(constant), eq(flag))
    }

    @Test
    fun testHasVibrator() {
        assertThat(vibratorHelper.hasVibrator()).isTrue()
        verify(vibrator).hasVibrator()
    }

    @Test
    fun testCancel() {
        vibratorHelper.cancel()
        verifyAsync().cancel()
    }

    private fun verifyAsync(): Vibrator {
        verify(executor).execute(backgroundTaskCaptor.capture())
        verify(vibrator).hasVibrator()
        backgroundTaskCaptor.value.run()

        return verify(vibrator)
    }
}