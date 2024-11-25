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

package com.android.systemui.qs.panels.ui.compose.selection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MutableSelectionStateTest : SysuiTestCase() {
    private val underTest = MutableSelectionState()

    @Test
    fun selectTile_isCorrectlySelected() {
        assertThat(underTest.selection?.tileSpec).isNotEqualTo(TEST_SPEC)

        underTest.select(TEST_SPEC, manual = true)
        assertThat(underTest.selection?.tileSpec).isEqualTo(TEST_SPEC)
        assertThat(underTest.selection?.manual).isTrue()

        underTest.unSelect()
        assertThat(underTest.selection).isNull()

        val newSpec = TileSpec.create("newSpec")
        underTest.select(TEST_SPEC, manual = true)
        underTest.select(newSpec, manual = false)
        assertThat(underTest.selection?.tileSpec).isNotEqualTo(TEST_SPEC)
        assertThat(underTest.selection?.tileSpec).isEqualTo(newSpec)
        assertThat(underTest.selection?.manual).isFalse()
    }

    companion object {
        private val TEST_SPEC = TileSpec.create("testSpec")
    }
}
