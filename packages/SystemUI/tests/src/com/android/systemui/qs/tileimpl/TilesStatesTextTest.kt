/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.tileimpl

import android.testing.AndroidTestingRunner
import androidx.test.filters.MediumTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@MediumTest
class TilesStatesTextTest : SysuiTestCase() {

    @Test
    fun testStockTilesHaveStatesArray() {
        val tiles = mContext.getString(R.string.quick_settings_tiles_stock).split(",")
        tiles.forEach { spec ->
            val resName = "${QSTileViewImpl.TILE_STATE_RES_PREFIX}$spec"
            val resId = mContext.resources.getIdentifier(resName, "array", mContext.packageName)

            assertNotEquals("Missing resource for $resName", 0, resId)

            val array = mContext.resources.getStringArray(resId)

            assertEquals("Array for $spec is of wrong size", 3, array.size)
        }
    }

    @Test
    fun testDefaultArray() {
        val array = mContext.resources.getStringArray(R.array.tile_states_default)

        assertThat(array.size).isEqualTo(3)
    }

    @Test
    fun testStockTilesSubtitlesMap() {
        val tiles = mContext.getString(R.string.quick_settings_tiles_stock).split(",")
        tiles.forEach { spec ->
            val resName = "${QSTileViewImpl.TILE_STATE_RES_PREFIX}$spec"
            val resId = mContext.resources.getIdentifier(resName, "array", mContext.packageName)

            assertNotEquals("Missing resource for $resName", 0, resId)

            assertThat(SubtitleArrayMapping.getSubtitleId(spec)).isEqualTo(resId)
        }
    }

    @Test
    fun testStockTilesSubtitlesReturnsDefault_unknown() {
        assertThat(SubtitleArrayMapping.getSubtitleId("unknown"))
            .isEqualTo(R.array.tile_states_default)
    }

    @Test
    fun testStockTilesSubtitlesReturnsDefault_null() {
        assertThat(SubtitleArrayMapping.getSubtitleId(null))
            .isEqualTo(R.array.tile_states_default)
    }
}