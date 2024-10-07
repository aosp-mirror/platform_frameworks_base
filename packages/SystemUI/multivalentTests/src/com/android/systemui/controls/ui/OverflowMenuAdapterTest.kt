/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.controls.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class OverflowMenuAdapterTest : SysuiTestCase() {

    @Test
    fun testGetItemId() {
        val ids = listOf(27L, 73L)
        val labels = listOf("first", "second")
        val adapter =
            OverflowMenuAdapter(
                context,
                layoutId = 0,
                labels.zip(ids).map { OverflowMenuAdapter.MenuItem(it.first, it.second) }
            ) {
                true
            }

        ids.forEachIndexed { index, id -> assertThat(adapter.getItemId(index)).isEqualTo(id) }
    }

    @Test
    fun testCheckEnabled() {
        val ids = listOf(27L, 73L)
        val labels = listOf("first", "second")
        val adapter =
            OverflowMenuAdapter(
                context,
                layoutId = 0,
                labels.zip(ids).map { OverflowMenuAdapter.MenuItem(it.first, it.second) }
            ) { position ->
                position == 0
            }

        assertThat(adapter.isEnabled(0)).isTrue()
        assertThat(adapter.isEnabled(1)).isFalse()
    }
}
