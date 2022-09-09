/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.power.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.power.data.repository.FakePowerRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class PowerInteractorTest : SysuiTestCase() {

    private lateinit var underTest: PowerInteractor
    private lateinit var repository: FakePowerRepository

    @Before
    fun setUp() {
        repository =
            FakePowerRepository(
                initialInteractive = true,
            )
        underTest = PowerInteractor(repository = repository)
    }

    @Test
    fun `isInteractive - screen turns off`() =
        runBlocking(IMMEDIATE) {
            repository.setInteractive(true)
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)

            repository.setInteractive(false)

            assertThat(value).isFalse()
            job.cancel()
        }

    @Test
    fun `isInteractive - becomes interactive`() =
        runBlocking(IMMEDIATE) {
            repository.setInteractive(false)
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)

            repository.setInteractive(true)

            assertThat(value).isTrue()
            job.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
