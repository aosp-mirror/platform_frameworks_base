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

package com.android.systemui.qs.composefragment.viewmodel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.testing.TestLifecycleOwner
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.testKosmos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
abstract class AbstractQSFragmentComposeViewModelTest : SysuiTestCase() {
    protected val kosmos = testKosmos()

    protected val lifecycleOwner =
        TestLifecycleOwner(
            initialState = Lifecycle.State.CREATED,
            coroutineDispatcher = kosmos.testDispatcher,
        )

    protected val underTest by lazy {
        kosmos.qsFragmentComposeViewModelFactory.create(lifecycleOwner.lifecycleScope)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(kosmos.testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    protected inline fun TestScope.testWithinLifecycle(
        crossinline block: suspend TestScope.() -> TestResult
    ): TestResult {
        return runTest {
            lifecycleOwner.setCurrentState(Lifecycle.State.RESUMED)
            lifecycleOwner.lifecycleScope.launch { underTest.activate() }
            block().also { lifecycleOwner.setCurrentState(Lifecycle.State.DESTROYED) }
        }
    }
}
