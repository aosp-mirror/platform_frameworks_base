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

package com.android.systemui.qs.panels.data.repository

import android.content.res.mainResources
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.server.display.feature.flags.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class StockTilesRepositoryTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply { mainResources = mContext.orCreateTestableResources.resources }

    @Test
    @EnableFlags(Flags.FLAG_EVEN_DIMMER)
    fun stockTilesMatchesResources_evenDimmerFlagOn_configOn() {
        // Enable the EvenDimmer config
        mContext
            .getOrCreateTestableResources()
            .addOverride(com.android.internal.R.bool.config_evenDimmerEnabled, true)
        val underTest = StockTilesRepository(kosmos.mainResources)

        val expected =
            kosmos.mainResources
                .getString(R.string.quick_settings_tiles_stock)
                .split(",")
                .filterNot { it.equals("reduce_brightness") }
                .map(TileSpec::create)
        assertThat(underTest.stockTiles).isEqualTo(expected)
    }

    @Test
    @EnableFlags(Flags.FLAG_EVEN_DIMMER)
    fun stockTilesMatchesResources_evenDimmerFlagOn_configOff() {
        // Disable the EvenDimmer config
        mContext
            .getOrCreateTestableResources()
            .addOverride(com.android.internal.R.bool.config_evenDimmerEnabled, false)
        val underTest = StockTilesRepository(kosmos.mainResources)

        val expected =
            kosmos.mainResources
                .getString(R.string.quick_settings_tiles_stock)
                .split(",")
                .map(TileSpec::create)
        assertThat(underTest.stockTiles).isEqualTo(expected)
    }

    @Test
    @DisableFlags(Flags.FLAG_EVEN_DIMMER)
    fun stockTilesMatchesResources_evenDimmerFlagOff() {
        val underTest = StockTilesRepository(kosmos.mainResources)

        val expected =
            kosmos.mainResources
                .getString(R.string.quick_settings_tiles_stock)
                .split(",")
                .map(TileSpec::create)
        assertThat(underTest.stockTiles).isEqualTo(expected)
    }
}
