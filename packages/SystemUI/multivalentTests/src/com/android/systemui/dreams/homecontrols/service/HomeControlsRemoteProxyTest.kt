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

package com.android.systemui.dreams.homecontrols.service

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dreams.homecontrols.shared.model.HomeControlsComponentInfo
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsRemoteProxyTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val fakeBinder = kosmos.fakeHomeControlsRemoteBinder

    private val underTest by lazy { kosmos.homeControlsRemoteProxy }

    @Test
    fun testRegistersOnlyWhileSubscribed() =
        testScope.runTest {
            assertThat(fakeBinder.callbacks).isEmpty()

            val job = launch { underTest.componentInfo.collect {} }
            runCurrent()
            assertThat(fakeBinder.callbacks).hasSize(1)

            job.cancel()
            runCurrent()
            assertThat(fakeBinder.callbacks).isEmpty()
        }

    @Test
    fun testEmitsOnCallback() =
        testScope.runTest {
            val componentInfo by collectLastValue(underTest.componentInfo)
            assertThat(componentInfo).isNull()

            fakeBinder.notifyCallbacks(TEST_COMPONENT, allowTrivialControlsOnLockscreen = true)
            assertThat(componentInfo)
                .isEqualTo(
                    HomeControlsComponentInfo(
                        TEST_COMPONENT,
                        allowTrivialControlsOnLockscreen = true,
                    )
                )
        }

    @Test
    fun testOnlyRegistersSingleCallbackForMultipleSubscribers() =
        testScope.runTest {
            assertThat(fakeBinder.callbacks).isEmpty()

            // 2 collectors
            val job = launch {
                launch { underTest.componentInfo.collect {} }
                launch { underTest.componentInfo.collect {} }
            }
            runCurrent()
            assertThat(fakeBinder.callbacks).hasSize(1)
            job.cancel()
        }

    private companion object {
        val TEST_COMPONENT = ComponentName("pkg.test", "class.test")
    }
}
