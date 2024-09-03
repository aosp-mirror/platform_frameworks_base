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

package com.android.systemui.qs.panels.ui.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.viewmodel.MockTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PaginatableGridLayoutTest : SysuiTestCase() {
    @Test
    fun correctRows_gapsAtEnd() {
        val columns = 6

        val sizedTiles =
            listOf(
                largeTile(),
                extraLargeTile(),
                largeTile(),
                smallTile(),
                largeTile(),
            )

        // [L L] [XL XL XL]
        // [L L] [S] [L L]

        val rows = PaginatableGridLayout.splitInRows(sizedTiles, columns)

        assertThat(rows).hasSize(2)
        assertThat(rows[0]).isEqualTo(sizedTiles.take(2))
        assertThat(rows[1]).isEqualTo(sizedTiles.drop(2))
    }

    @Test
    fun correctRows_fullLastRow_noEmptyRow() {
        val columns = 6

        val sizedTiles =
            listOf(
                largeTile(),
                extraLargeTile(),
                smallTile(),
            )

        // [L L] [XL XL XL] [S]

        val rows = PaginatableGridLayout.splitInRows(sizedTiles, columns)

        assertThat(rows).hasSize(1)
        assertThat(rows[0]).isEqualTo(sizedTiles)
    }

    companion object {
        fun extraLargeTile() = SizedTileImpl(MockTileViewModel(TileSpec.create("XLarge")), 3)

        fun largeTile() = SizedTileImpl(MockTileViewModel(TileSpec.create("large")), 2)

        fun smallTile() = SizedTileImpl(MockTileViewModel(TileSpec.create("small")), 1)
    }
}
