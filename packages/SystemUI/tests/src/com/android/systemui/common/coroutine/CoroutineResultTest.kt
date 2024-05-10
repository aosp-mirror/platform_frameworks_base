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
 */

package com.android.systemui.common.coroutine

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** atest SystemUITests:CoroutineResultTest */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class CoroutineResultTest : SysuiTestCase() {

    @Test
    fun suspendRunCatching_shouldReturnSuccess() = runTest {
        val actual = suspendRunCatching { "Placeholder" }
        assertThat(actual.isSuccess).isTrue()
        assertThat(actual.getOrNull()).isEqualTo("Placeholder")
    }

    @Test
    fun suspendRunCatching_whenExceptionThrow_shouldResumeWithException() = runTest {
        val actual = suspendRunCatching { throw Exception() }
        assertThat(actual.isFailure).isTrue()
        assertThat(actual.exceptionOrNull()).isInstanceOf(Exception::class.java)
    }

    @Test(expected = CancellationException::class)
    fun suspendRunCatching_whenCancelled_shouldResumeWithException() = runTest {
        suspendRunCatching { cancel() }
    }
}
