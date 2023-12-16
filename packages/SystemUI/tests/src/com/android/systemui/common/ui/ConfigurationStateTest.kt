/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.common.ui

import android.content.Context
import android.testing.AndroidTestingRunner
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.captureMany
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class ConfigurationStateTest : SysuiTestCase() {

    private val configurationController: ConfigurationController = mock()
    private val layoutInflater = TestLayoutInflater()
    private val backgroundDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(backgroundDispatcher)

    val underTest = ConfigurationState(configurationController, context, layoutInflater)

    @Test
    fun reinflateAndBindLatest_inflatesWithoutEmission() =
        testScope.runTest {
            var callbackCount = 0
            backgroundScope.launch {
                underTest.reinflateAndBindLatest<View>(
                    resource = 0,
                    root = null,
                    attachToRoot = false,
                    backgroundDispatcher,
                ) {
                    callbackCount++
                    null
                }
            }

            // Inflates without an emission
            runCurrent()
            assertThat(layoutInflater.inflationCount).isEqualTo(1)
            assertThat(callbackCount).isEqualTo(1)
        }

    @Test
    fun reinflateAndBindLatest_reinflatesOnThemeChanged() =
        testScope.runTest {
            var callbackCount = 0
            backgroundScope.launch {
                underTest.reinflateAndBindLatest<View>(
                    resource = 0,
                    root = null,
                    attachToRoot = false,
                    backgroundDispatcher,
                ) {
                    callbackCount++
                    null
                }
            }
            runCurrent()

            val configListeners: List<ConfigurationController.ConfigurationListener> = captureMany {
                verify(configurationController, atLeastOnce()).addCallback(capture())
            }

            listOf(1, 2, 3).forEach { count ->
                assertThat(layoutInflater.inflationCount).isEqualTo(count)
                assertThat(callbackCount).isEqualTo(count)
                configListeners.forEach { it.onThemeChanged() }
                runCurrent()
            }
        }

    @Test
    fun reinflateAndBindLatest_reinflatesOnDensityOrFontScaleChanged() =
        testScope.runTest {
            var callbackCount = 0
            backgroundScope.launch {
                underTest.reinflateAndBindLatest<View>(
                    resource = 0,
                    root = null,
                    attachToRoot = false,
                    backgroundDispatcher,
                ) {
                    callbackCount++
                    null
                }
            }
            runCurrent()

            val configListeners: List<ConfigurationController.ConfigurationListener> = captureMany {
                verify(configurationController, atLeastOnce()).addCallback(capture())
            }

            listOf(1, 2, 3).forEach { count ->
                assertThat(layoutInflater.inflationCount).isEqualTo(count)
                assertThat(callbackCount).isEqualTo(count)
                configListeners.forEach { it.onDensityOrFontScaleChanged() }
                runCurrent()
            }
        }

    @Test
    fun testReinflateAndBindLatest_disposesOnCancel() =
        testScope.runTest {
            var callbackCount = 0
            var disposed = false
            val job = launch {
                underTest.reinflateAndBindLatest<View>(
                    resource = 0,
                    root = null,
                    attachToRoot = false,
                    backgroundDispatcher,
                ) {
                    callbackCount++
                    DisposableHandle { disposed = true }
                }
            }

            runCurrent()
            job.cancelAndJoin()
            assertThat(disposed).isTrue()
        }

    inner class TestLayoutInflater : LayoutInflater(context) {

        var inflationCount = 0

        override fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View {
            inflationCount++
            return View(context)
        }

        override fun cloneInContext(p0: Context?): LayoutInflater {
            // not needed for this test
            return this
        }
    }
}
