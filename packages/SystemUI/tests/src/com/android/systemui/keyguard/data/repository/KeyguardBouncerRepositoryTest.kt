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
 */

package com.android.systemui.keyguard.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardBouncerRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var viewMediatorCallback: ViewMediatorCallback
    @Mock private lateinit var bouncerLogger: TableLogBuffer
    lateinit var underTest: KeyguardBouncerRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        val testCoroutineScope = TestCoroutineScope()
        underTest =
            KeyguardBouncerRepositoryImpl(
                systemClock,
                testCoroutineScope,
                bouncerLogger,
            )
    }

    @Test
    fun changingFlowValueTriggersLogging() = runBlocking {
        underTest.setPrimaryShow(true)
        verify(bouncerLogger).logChange(eq(""), eq("PrimaryBouncerShow"), value = eq(false), any())
    }
}
