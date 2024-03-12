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

package com.android.settingslib.spa.framework.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionsTest {
    @Test
    fun testAsyncForEach() = runTest {
        var sum = 0
        listOf(1, 2, 3).asyncForEach { sum += it }
        Truth.assertThat(sum).isEqualTo(6)
    }

    @Test
    fun testAsyncFilter() = runTest {
        val res = listOf(1, 2, 3).asyncFilter { it >= 2 }
        Truth.assertThat(res).containsExactly(2, 3).inOrder()
    }

    @Test
    fun testAsyncMap() = runTest {
        val res = listOf(1, 2, 3).asyncMap { it + 1 }
        Truth.assertThat(res).containsExactly(2, 3, 4).inOrder()
    }
}
