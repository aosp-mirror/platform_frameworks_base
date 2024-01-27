/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.statusbar.policy.CallbackController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class CallbackControllerAutoAddableTest : SysuiTestCase() {

    @Test
    fun callbackAddedAndRemoved() = runTest {
        val controller = TestableController()
        val callback = object : TestableController.Callback {}
        val underTest =
            object :
                CallbackControllerAutoAddable<TestableController.Callback, TestableController>(
                    controller
                ) {
                override val description: String = ""
                override val spec: TileSpec
                    get() = SPEC

                override fun ProducerScope<AutoAddSignal>.getCallback():
                    TestableController.Callback {
                    return callback
                }
            }

        val job = launch { underTest.autoAddSignal(0).collect {} }
        runCurrent()
        assertThat(controller.callbacks).containsExactly(callback)
        job.cancel()
        runCurrent()
        assertThat(controller.callbacks).isEmpty()
    }

    @Test
    fun sendAddFromCallback() = runTest {
        val controller = TestableController()
        val underTest =
            object :
                CallbackControllerAutoAddable<TestableController.Callback, TestableController>(
                    controller
                ) {
                override val description: String = ""

                override val spec: TileSpec
                    get() = SPEC

                override fun ProducerScope<AutoAddSignal>.getCallback():
                    TestableController.Callback {
                    return object : TestableController.Callback {
                        override fun change() {
                            sendAdd()
                        }
                    }
                }
            }

        val signal by collectLastValue(underTest.autoAddSignal(0))
        assertThat(signal).isNull()

        controller.callbacks.first().change()

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun strategyIfNotAdded() {
        val underTest =
            object :
                CallbackControllerAutoAddable<TestableController.Callback, TestableController>(
                    TestableController()
                ) {
                override val description: String = ""
                override val spec: TileSpec
                    get() = SPEC

                override fun ProducerScope<AutoAddSignal>.getCallback():
                    TestableController.Callback {
                    return object : TestableController.Callback {}
                }
            }

        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.IfNotAdded(SPEC))
    }

    private class TestableController : CallbackController<TestableController.Callback> {

        val callbacks = mutableSetOf<Callback>()

        override fun addCallback(listener: Callback) {
            callbacks.add(listener)
        }

        override fun removeCallback(listener: Callback) {
            callbacks.remove(listener)
        }

        interface Callback {
            fun change() {}
        }
    }

    companion object {
        private val SPEC = TileSpec.create("test")
    }
}
