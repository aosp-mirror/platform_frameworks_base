/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.shared

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TileSpecTest : SysuiTestCase() {

    @Test
    fun platformTile() {
        val spec = "spec"

        val tileSpec = TileSpec.create(spec)

        assertThat(tileSpec is TileSpec.PlatformTileSpec).isTrue()
        assertThat(tileSpec.spec).isEqualTo(spec)
    }

    @Test
    fun customTile() {
        val componentName = ComponentName("test_pkg", "test_cls")
        val spec = CUSTOM_TILE_PREFIX + componentName.flattenToString() + ")"

        val tileSpec = TileSpec.create(spec)

        assertThat(tileSpec is TileSpec.CustomTileSpec).isTrue()
        assertThat(tileSpec.spec).isEqualTo(spec)
        assertThat((tileSpec as TileSpec.CustomTileSpec).componentName).isEqualTo(componentName)
    }

    @Test
    fun emptyCustomTile_invalid() {
        val spec = CUSTOM_TILE_PREFIX + ")"

        val tileSpec = TileSpec.create(spec)

        assertThat(tileSpec).isEqualTo(TileSpec.Invalid)
    }

    @Test
    fun invalidCustomTileSpec_invalid() {
        val spec = CUSTOM_TILE_PREFIX + "invalid)"

        val tileSpec = TileSpec.create(spec)

        assertThat(tileSpec).isEqualTo(TileSpec.Invalid)
    }

    @Test
    fun customTileNotEndsWithParenthesis_invalid() {
        val componentName = ComponentName("test_pkg", "test_cls")
        val spec = CUSTOM_TILE_PREFIX + componentName.flattenToString()

        val tileSpec = TileSpec.create(spec)

        assertThat(tileSpec).isEqualTo(TileSpec.Invalid)
    }

    @Test
    fun emptySpec_invalid() {
        assertThat(TileSpec.create("")).isEqualTo(TileSpec.Invalid)
    }

    companion object {
        private const val CUSTOM_TILE_PREFIX = "custom("
    }
}
