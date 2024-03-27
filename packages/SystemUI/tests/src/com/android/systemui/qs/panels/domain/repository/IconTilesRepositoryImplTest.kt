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

package com.android.systemui.qs.panels.domain.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.panels.data.repository.IconTilesRepositoryImpl
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class IconTilesRepositoryImplTest : SysuiTestCase() {

    private val underTest = IconTilesRepositoryImpl()

    @Test
    fun iconTilesSpecsIsValid() = runTest {
        val tilesSpecs by collectLastValue(underTest.iconTilesSpecs)
        assertThat(tilesSpecs).isEqualTo(ICON_ONLY_TILES_SPECS)
    }

    companion object {
        private val ICON_ONLY_TILES_SPECS =
            setOf(
                TileSpec.create("airplane"),
                TileSpec.create("battery"),
                TileSpec.create("cameratoggle"),
                TileSpec.create("cast"),
                TileSpec.create("color_correction"),
                TileSpec.create("inversion"),
                TileSpec.create("saver"),
                TileSpec.create("dnd"),
                TileSpec.create("flashlight"),
                TileSpec.create("location"),
                TileSpec.create("mictoggle"),
                TileSpec.create("nfc"),
                TileSpec.create("night"),
                TileSpec.create("rotation")
            )
    }
}
