/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.systemui.bouncer.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.SystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardBouncerRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var bouncerLogger: TableLogBuffer

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    lateinit var underTest: KeyguardBouncerRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            object :
                KeyguardBouncerRepositoryImpl(
                    systemClock,
                    testScope.backgroundScope,
                    bouncerLogger,
                ) {
                override fun isDebuggable(): Boolean = true
            }
    }

    @Test
    fun changingFlowValueTriggersLogging() =
        testScope.runTest {
            underTest.setPrimaryShow(true)
            runCurrent()
            Mockito.verify(bouncerLogger)
                .logChange(eq(""), eq("PrimaryBouncerShow"), value = eq(false), any())
        }

    @Test
    fun primaryStartDisappearAnimation() =
        testScope.runTest {
            assertThat(underTest.isPrimaryBouncerStartingDisappearAnimation()).isFalse()

            underTest.setPrimaryStartDisappearAnimation(Runnable {})
            assertThat(underTest.isPrimaryBouncerStartingDisappearAnimation()).isTrue()

            underTest.setPrimaryStartDisappearAnimation(null)
            assertThat(underTest.isPrimaryBouncerStartingDisappearAnimation()).isFalse()

            val disappearFlow by collectValues(underTest.primaryBouncerStartingDisappearAnimation)
            underTest.setPrimaryStartDisappearAnimation(null)
            assertThat(disappearFlow[0]).isNull()

            // Now issue two in a row to make sure one is not dropped
            underTest.setPrimaryStartDisappearAnimation(Runnable {})
            underTest.setPrimaryStartDisappearAnimation(null)
            assertThat(disappearFlow[1]).isNotNull()
            assertThat(disappearFlow[2]).isNull()
        }
}
