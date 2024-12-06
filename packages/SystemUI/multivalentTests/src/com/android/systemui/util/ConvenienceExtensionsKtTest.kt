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

package com.android.systemui.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ConvenienceExtensionsKtTest : SysuiTestCase() {

    @Test
    fun containsExactly_notDuplicatedElements_allSame_returnsTrue() {
        val list = listOf(1, 2, 3)

        assertThat(list.containsExactly(2, 1, 3)).isTrue()
    }

    @Test
    fun containsExactly_duplicatedElements_allSame_returnsTrue() {
        val list = listOf(1, 1, 2, 3, 3)

        assertThat(list.containsExactly(1, 1, 2, 3, 3)).isTrue()
    }

    @Test
    fun containsExactly_duplicatedElements_sameButNotDuplicated_returnsFalse() {
        val list = listOf(1, 1, 2, 3, 3)

        assertThat(list.containsExactly(1, 2, 3)).isFalse()
    }

    @Test
    fun containsExactly_duplicatedElements_sameButNotSameAmount_returnsFalse() {
        val list = listOf(1, 1, 2, 3, 3)

        assertThat(list.containsExactly(1, 2, 2, 3, 3)).isFalse()
    }

    @Test
    fun eachCountMap_returnsExpectedCount() {
        val list = listOf(1, 3, 1, 3, 3, 3, 2)

        assertThat(list.eachCountMap()).isEqualTo(mapOf(1 to 2, 2 to 1, 3 to 4))
    }
}
