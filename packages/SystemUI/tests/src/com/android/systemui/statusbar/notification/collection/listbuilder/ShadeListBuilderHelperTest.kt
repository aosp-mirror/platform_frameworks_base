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
package com.android.systemui.statusbar.notification.collection.listbuilder

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderHelper.getContiguousSubLists
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ShadeListBuilderHelperTest : SysuiTestCase() {

    @Test
    fun testGetContiguousSubLists() {
        assertThat(getContiguousSubLists("AAAAAA".toList()) { it })
            .containsExactly(
                listOf('A', 'A', 'A', 'A', 'A', 'A'),
            )
            .inOrder()
        assertThat(getContiguousSubLists("AAABBB".toList()) { it })
            .containsExactly(
                listOf('A', 'A', 'A'),
                listOf('B', 'B', 'B'),
            )
            .inOrder()
        assertThat(getContiguousSubLists("AAABAA".toList()) { it })
            .containsExactly(
                listOf('A', 'A', 'A'),
                listOf('B'),
                listOf('A', 'A'),
            )
            .inOrder()
        assertThat(getContiguousSubLists("AAABAA".toList(), minLength = 2) { it })
            .containsExactly(
                listOf('A', 'A', 'A'),
                listOf('A', 'A'),
            )
            .inOrder()
        assertThat(getContiguousSubLists("AAABBBBCCDEEE".toList()) { it })
            .containsExactly(
                listOf('A', 'A', 'A'),
                listOf('B', 'B', 'B', 'B'),
                listOf('C', 'C'),
                listOf('D'),
                listOf('E', 'E', 'E'),
            )
            .inOrder()
        assertThat(getContiguousSubLists("AAABBBBCCDEEE".toList(), minLength = 2) { it })
            .containsExactly(
                listOf('A', 'A', 'A'),
                listOf('B', 'B', 'B', 'B'),
                listOf('C', 'C'),
                listOf('E', 'E', 'E'),
            )
            .inOrder()
    }
}
