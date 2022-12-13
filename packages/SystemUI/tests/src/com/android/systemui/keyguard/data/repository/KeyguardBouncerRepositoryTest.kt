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

import androidx.test.filters.SmallTest
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardBouncerRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var viewMediatorCallback: ViewMediatorCallback
    @Mock private lateinit var bouncerLogger: TableLogBuffer
    lateinit var underTest: KeyguardBouncerRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        val testCoroutineScope = TestCoroutineScope()
        underTest =
            KeyguardBouncerRepository(viewMediatorCallback, testCoroutineScope, bouncerLogger)
    }

    @Test
    fun changingFlowValueTriggersLogging() = runBlocking {
        underTest.setPrimaryHide(true)
        verify(bouncerLogger).logChange("", "PrimaryBouncerHide", false)
    }
}
