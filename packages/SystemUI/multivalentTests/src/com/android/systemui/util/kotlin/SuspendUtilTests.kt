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

package com.android.systemui.util.kotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class RaceSuspendTest : SysuiTestCase() {
    @Test
    fun raceSimple() = runBlocking {
        val winner = CompletableDeferred<Int>()
        val result = async {
            race(
                { winner.await() },
                { awaitCancellation() },
            )
        }
        winner.complete(1)
        assertThat(result.await()).isEqualTo(1)
    }

    @Test
    fun raceImmediate() = runBlocking {
        assertThat(
                race<Int>(
                    { 1 },
                    { 2 },
                )
            )
            .isEqualTo(1)
    }
}
