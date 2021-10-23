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

package com.android.systemui.idle

import android.content.res.Resources
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`

@SmallTest
@RunWith(AndroidTestingRunner::class)
class LightSensorDebounceAlgorithmTest : SysuiTestCase() {
    @Mock private lateinit var resources: Resources

    private val systemClock = FakeSystemClock()
    private val executor = FakeExecutor(systemClock)

    private lateinit var algorithm: LightSensorEventsDebounceAlgorithm

    private val mockLightModeThreshold = 5
    private val mockDarkModeThreshold = 2
    private val mockLightModeSpan = 100
    private val mockDarkModeSpan = 50
    private val mockLightModeFrequency = 10
    private val mockDarkModeFrequency = 5

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(resources.getInteger(R.integer.config_ambientLightModeThreshold))
                .thenReturn(mockLightModeThreshold)
        `when`(resources.getInteger(R.integer.config_ambientDarkModeThreshold))
                .thenReturn(mockDarkModeThreshold)
        `when`(resources.getInteger(R.integer.config_ambientLightModeSamplingSpanMillis))
                .thenReturn(mockLightModeSpan)
        `when`(resources.getInteger(R.integer.config_ambientDarkModeSamplingSpanMillis))
                .thenReturn(mockDarkModeSpan)
        `when`(resources.getInteger(R.integer.config_ambientLightModeSamplingFrequencyMillis))
                .thenReturn(mockLightModeFrequency)
        `when`(resources.getInteger(R.integer.config_ambientDarkModeSamplingFrequencyMillis))
                .thenReturn(mockDarkModeFrequency)

        algorithm = LightSensorEventsDebounceAlgorithm(executor, resources)
    }

    @Test
    fun shouldOnlyTriggerCallbackWhenValueChanges() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        algorithm.start(callback)

        // Light mode, should trigger callback.
        algorithm.mode = AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)
        reset(callback)

        // Light mode again, should NOT trigger callback.
        algorithm.mode = AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT
        verify(callback, never()).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)
        reset(callback)

        // Dark mode, should trigger callback.
        algorithm.mode = AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)
        reset(callback)

        // Dark mode again, should not trigger callback.
        algorithm.mode = AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK
        verify(callback, never()).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)
    }

    @Test
    fun shouldReportUndecidedWhenNeitherLightNorDarkClaimIsTrue() {
        algorithm.isDarkMode = false
        algorithm.isLightMode = false

        assertThat(algorithm.mode).isEqualTo(
                AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED)
    }

    @Test
    fun shouldReportDarkModeAsLongAsDarkModeClaimIsTrue() {
        algorithm.isDarkMode = true
        algorithm.isLightMode = false

        assertThat(algorithm.mode).isEqualTo(
                AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)

        algorithm.isLightMode = true
        assertThat(algorithm.mode).isEqualTo(
                AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)
    }

    @Test
    fun shouldReportLightModeWhenLightModeClaimIsTrueAndDarkModeClaimIsFalse() {
        algorithm.isLightMode = true
        algorithm.isDarkMode = false

        assertThat(algorithm.mode).isEqualTo(
                AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)
    }

    @Test
    fun shouldSetIsLightModeToTrueWhenBundleAverageIsGreaterThanThreshold() {
        // Note: [mockLightModeThreshold] is 5.0.
        algorithm.bundleAverageLightMode = 5.1
        assertThat(algorithm.isLightMode).isTrue()

        algorithm.bundleAverageLightMode = 10.0
        assertThat(algorithm.isLightMode).isTrue()

        algorithm.bundleAverageLightMode = 20.0
        assertThat(algorithm.isLightMode).isTrue()

        algorithm.bundleAverageLightMode = 5.0
        assertThat(algorithm.isLightMode).isFalse()

        algorithm.bundleAverageLightMode = 3.0
        assertThat(algorithm.isLightMode).isFalse()

        algorithm.bundleAverageLightMode = 0.0
        assertThat(algorithm.isLightMode).isFalse()
    }

    @Test
    fun shouldSetIsDarkModeToTrueWhenBundleAverageIsLessThanThreshold() {
        // Note: [mockDarkModeThreshold] is 2.0.
        algorithm.bundleAverageDarkMode = 1.9
        assertThat(algorithm.isDarkMode).isTrue()

        algorithm.bundleAverageDarkMode = 1.0
        assertThat(algorithm.isDarkMode).isTrue()

        algorithm.bundleAverageDarkMode = 0.0
        assertThat(algorithm.isDarkMode).isTrue()

        algorithm.bundleAverageDarkMode = 2.0
        assertThat(algorithm.isDarkMode).isFalse()

        algorithm.bundleAverageDarkMode = 3.0
        assertThat(algorithm.isDarkMode).isFalse()

        algorithm.bundleAverageDarkMode = 10.0
        assertThat(algorithm.isDarkMode).isFalse()
    }

    @Test
    fun shouldCorrectlyCalculateAverageFromABundle() {
        // For light mode.
        algorithm.bundleLightMode = arrayListOf(1.0f, 3.0f, 5.0f, 7.0f)
        assertThat(algorithm.bundleAverageLightMode).isEqualTo(4.0)

        algorithm.bundleLightMode = arrayListOf(2.0f, 4.0f, 6.0f, 8.0f)
        assertThat(algorithm.bundleAverageLightMode).isEqualTo(5.0)

        // For dark mode.
        algorithm.bundleDarkMode = arrayListOf(1.0f, 3.0f, 5.0f, 7.0f, 9.0f)
        assertThat(algorithm.bundleAverageDarkMode).isEqualTo(5.0)

        algorithm.bundleDarkMode = arrayListOf(2.0f, 4.0f, 6.0f, 8.0f, 10.0f)
        assertThat(algorithm.bundleAverageDarkMode).isEqualTo(6.0)
    }

    @Test
    fun shouldAddSensorEventUpdatesToBundles() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        // On start() one bundle is created for light and dark mode each.
        algorithm.start(callback)
        executor.runAllReady()

        // Add 1 more bundle to queue for each mode.
        algorithm.bundlesQueueLightMode.add(ArrayList())
        algorithm.bundlesQueueDarkMode.add(ArrayList())

        algorithm.onUpdateLightSensorEvent(1.0f)
        algorithm.onUpdateLightSensorEvent(1.0f)
        algorithm.onUpdateLightSensorEvent(2.0f)
        algorithm.onUpdateLightSensorEvent(3.0f)
        algorithm.onUpdateLightSensorEvent(4.0f)

        val expectedValues = listOf(1.0f, 1.0f, 2.0f, 3.0f, 4.0f)

        assertBundleContainsAll(algorithm.bundlesQueueLightMode[0], expectedValues)
        assertBundleContainsAll(algorithm.bundlesQueueLightMode[1], expectedValues)
        assertBundleContainsAll(algorithm.bundlesQueueDarkMode[0], expectedValues)
        assertBundleContainsAll(algorithm.bundlesQueueDarkMode[1], expectedValues)
    }

    @Test
    fun shouldCorrectlyEnqueueLightModeBundles() {
        assertThat(algorithm.bundlesQueueLightMode.size).isEqualTo(0)

        algorithm.enqueueLightModeBundle.run()
        assertThat(algorithm.bundlesQueueLightMode.size).isEqualTo(1)

        algorithm.enqueueLightModeBundle.run()
        assertThat(algorithm.bundlesQueueLightMode.size).isEqualTo(2)

        algorithm.enqueueLightModeBundle.run()
        assertThat(algorithm.bundlesQueueLightMode.size).isEqualTo(3)

        // Verifies dark mode bundles queue is not impacted.
        assertThat(algorithm.bundlesQueueDarkMode.size).isEqualTo(0)
    }

    @Test
    fun shouldCorrectlyEnqueueDarkModeBundles() {
        assertThat(algorithm.bundlesQueueDarkMode.size).isEqualTo(0)

        algorithm.enqueueDarkModeBundle.run()
        assertThat(algorithm.bundlesQueueDarkMode.size).isEqualTo(1)

        algorithm.enqueueDarkModeBundle.run()
        assertThat(algorithm.bundlesQueueDarkMode.size).isEqualTo(2)

        algorithm.enqueueDarkModeBundle.run()
        assertThat(algorithm.bundlesQueueDarkMode.size).isEqualTo(3)

        // Verifies light mode bundles queue is not impacted.
        assertThat(algorithm.bundlesQueueLightMode.size).isEqualTo(0)
    }

    @Test
    fun shouldCorrectlyDequeueLightModeBundles() {
        // Sets up the light mode bundles queue.
        val bundle1 = arrayListOf(1.0f, 3.0f, 6.0f, 9.0f)
        val bundle2 = arrayListOf(5.0f, 10f)
        val bundle3 = arrayListOf(2.0f, 4.0f)
        algorithm.bundlesQueueLightMode.add(bundle1)
        algorithm.bundlesQueueLightMode.add(bundle2)
        algorithm.bundlesQueueLightMode.add(bundle3)

        // The committed bundle should be the first one in queue.
        algorithm.dequeueLightModeBundle.run()
        assertBundleContainsAll(algorithm.bundleLightMode, bundle1)

        algorithm.dequeueLightModeBundle.run()
        assertBundleContainsAll(algorithm.bundleLightMode, bundle2)

        algorithm.dequeueLightModeBundle.run()
        assertBundleContainsAll(algorithm.bundleLightMode, bundle3)

        // Verifies that the dark mode bundle is not impacted.
        assertBundleContainsAll(algorithm.bundleDarkMode, listOf())
    }

    @Test
    fun shouldCorrectlyDequeueDarkModeBundles() {
        // Sets up the dark mode bundles queue.
        val bundle1 = arrayListOf(2.0f, 4.0f)
        val bundle2 = arrayListOf(5.0f, 10f)
        val bundle3 = arrayListOf(1.0f, 3.0f, 6.0f, 9.0f)
        algorithm.bundlesQueueDarkMode.add(bundle1)
        algorithm.bundlesQueueDarkMode.add(bundle2)
        algorithm.bundlesQueueDarkMode.add(bundle3)

        // The committed bundle should be the first one in queue.
        algorithm.dequeueDarkModeBundle.run()
        assertBundleContainsAll(algorithm.bundleDarkMode, bundle1)

        algorithm.dequeueDarkModeBundle.run()
        assertBundleContainsAll(algorithm.bundleDarkMode, bundle2)

        algorithm.dequeueDarkModeBundle.run()
        assertBundleContainsAll(algorithm.bundleDarkMode, bundle3)

        // Verifies that the light mode bundle is not impacted.
        assertBundleContainsAll(algorithm.bundleLightMode, listOf())
    }

    @Test
    fun shouldSetLightSensorLevelFromSensorEventUpdates() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        algorithm.start(callback)

        algorithm.onUpdateLightSensorEvent(1.0f)
        assertThat(algorithm.lightSensorLevel).isEqualTo(1.0f)

        algorithm.onUpdateLightSensorEvent(10.0f)
        assertThat(algorithm.lightSensorLevel).isEqualTo(10.0f)

        algorithm.onUpdateLightSensorEvent(0.0f)
        assertThat(algorithm.lightSensorLevel).isEqualTo(0.0f)
    }

    @Test
    fun shouldRippleFromSensorEventUpdatesDownToAmbientLightMode() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        algorithm.start(callback)
        executor.runAllReady()

        // Sensor event updates.
        algorithm.onUpdateLightSensorEvent(10.0f)
        algorithm.onUpdateLightSensorEvent(15.0f)
        algorithm.onUpdateLightSensorEvent(12.0f)
        algorithm.onUpdateLightSensorEvent(10.0f)

        // Advances time so both light and dark claims have been calculated.
        systemClock.advanceTime((mockLightModeSpan + 1).toLong())

        // Verifies the callback is triggered the ambient mode has changed LIGHT.
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)
    }

    @Test
    fun shouldRippleFromSensorEventUpdatesDownToAmbientDarkMode() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        algorithm.start(callback)
        executor.runAllReady()

        // Sensor event updates.
        algorithm.onUpdateLightSensorEvent(1.0f)
        algorithm.onUpdateLightSensorEvent(0.5f)
        algorithm.onUpdateLightSensorEvent(1.2f)
        algorithm.onUpdateLightSensorEvent(0.8f)

        // Advances time so both light and dark claims have been calculated.
        systemClock.advanceTime((mockLightModeSpan + 1).toLong())

        // Verifies the callback is triggered the ambient mode has changed DARK.
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)
    }

    // Asserts that [bundle] contains the same elements as [expected], not necessarily in the same
    // order.
    private fun assertBundleContainsAll(bundle: ArrayList<Float>, expected: Collection<Float>) {
        assertThat(bundle.size).isEqualTo(expected.size)
        assertThat(bundle.containsAll(expected))
    }
}