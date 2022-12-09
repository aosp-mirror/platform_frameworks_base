/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.graphics.PointF
import android.graphics.RectF
import android.hardware.biometrics.SensorLocationInternal
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.Surface
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.nullable
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val SENSOR_X = 50
private const val SENSOR_Y = 250
private const val SENSOR_RADIUS = 10

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class UdfpsViewTest : SysuiTestCase() {

    @JvmField @Rule
    var rule = MockitoJUnit.rule()

    @Mock
    lateinit var hbmProvider: UdfpsDisplayModeProvider
    @Mock
    lateinit var animationViewController: UdfpsAnimationViewController<UdfpsAnimationView>

    private lateinit var view: UdfpsView

    @Before
    fun setup() {
        context.setTheme(R.style.Theme_AppCompat)
        view = LayoutInflater.from(context).inflate(R.layout.udfps_view, null) as UdfpsView
        view.animationViewController = animationViewController
        val sensorBounds = SensorLocationInternal("", SENSOR_X, SENSOR_Y, SENSOR_RADIUS).rect
        view.overlayParams = UdfpsOverlayParams(sensorBounds, 1920, 1080, 1f, Surface.ROTATION_0)
        view.setUdfpsDisplayModeProvider(hbmProvider)
        ViewUtils.attachView(view)
    }

    @After
    fun cleanup() {
        ViewUtils.detachView(view)
    }

    @Test
    fun forwardsEvents() {
        view.dozeTimeTick()
        verify(animationViewController).dozeTimeTick()

        view.onTouchOutsideView()
        verify(animationViewController).onTouchOutsideView()
    }

    @Test
    fun layoutSizeFitsSensor() {
        val params = withArgCaptor<RectF> {
            verify(animationViewController).onSensorRectUpdated(capture())
        }
        assertThat(params.width()).isAtLeast(2f * SENSOR_RADIUS)
        assertThat(params.height()).isAtLeast(2f * SENSOR_RADIUS)
    }

    @Test
    fun isWithinSensorAreaAndPaused() = isWithinSensorArea(paused = true)

    @Test
    fun isWithinSensorAreaAndNotPaused() = isWithinSensorArea(paused = false)

    private fun isWithinSensorArea(paused: Boolean) {
        whenever(animationViewController.shouldPauseAuth()).thenReturn(paused)
        whenever(animationViewController.touchTranslation).thenReturn(PointF(0f, 0f))
        val end = (SENSOR_RADIUS * 2) - 1
        for (x in 1 until end) {
            for (y in 1 until end) {
                assertThat(view.isWithinSensorArea(x.toFloat(), y.toFloat())).isEqualTo(!paused)
            }
        }
    }

    @Test
    fun isWithinSensorAreaWhenTranslated() {
        val offset = PointF(100f, 200f)
        whenever(animationViewController.touchTranslation).thenReturn(offset)
        val end = (SENSOR_RADIUS * 2) - 1
        for (x in 0 until offset.x.toInt() step 2) {
            for (y in 0 until offset.y.toInt() step 2) {
                assertThat(view.isWithinSensorArea(x.toFloat(), y.toFloat())).isFalse()
            }
        }
        for (x in offset.x.toInt() + 1 until offset.x.toInt() + end) {
            for (y in offset.y.toInt() + 1 until offset.y.toInt() + end) {
                assertThat(view.isWithinSensorArea(x.toFloat(), y.toFloat())).isTrue()
            }
        }
    }

    @Test
    fun isNotWithinSensorArea() {
        whenever(animationViewController.touchTranslation).thenReturn(PointF(0f, 0f))
        assertThat(view.isWithinSensorArea(SENSOR_RADIUS * 2.5f, SENSOR_RADIUS.toFloat())).isFalse()
        assertThat(view.isWithinSensorArea(SENSOR_RADIUS.toFloat(), SENSOR_RADIUS * 2.5f)).isFalse()
    }

    @Test
    fun startAndStopIllumination() {
        val onDone: Runnable = mock()
        view.configureDisplay(onDone)

        val illuminator = withArgCaptor<Runnable> {
            verify(hbmProvider).enable(capture())
        }

        assertThat(view.isDisplayConfigured).isTrue()
        verify(animationViewController).onDisplayConfiguring()
        verify(animationViewController, never()).onDisplayUnconfigured()
        verify(onDone, never()).run()

        // fake illumination event
        illuminator.run()
        waitForLooper()
        verify(onDone).run()
        verify(hbmProvider, never()).disable(any())

        view.unconfigureDisplay()
        assertThat(view.isDisplayConfigured).isFalse()
        verify(animationViewController).onDisplayUnconfigured()
        verify(hbmProvider).disable(nullable(Runnable::class.java))
    }

    private fun waitForLooper() = TestableLooper.get(this).processAllMessages()
}
