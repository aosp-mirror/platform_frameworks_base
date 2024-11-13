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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.deviceconfig.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.fakeDeviceConfigProxy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceConfigRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val dataSource = kosmos.fakeDeviceConfigProxy

    private val underTest = kosmos.deviceConfigRepository

    @Test
    fun booleanProperty() =
        testScope.runTest {
            val property by collectLastValue(underTest.property("namespace", "name", false))
            assertThat(property).isFalse()

            dataSource.setProperty("namespace", "name", "true", /* makeDefault= */ false)
            kosmos.fakeExecutor.runAllReady()
            assertThat(property).isTrue()

            dataSource.setProperty("namespace", "name", "false", /* makeDefault= */ false)
            kosmos.fakeExecutor.runAllReady()
            assertThat(property).isFalse()
        }
}
