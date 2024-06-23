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
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.ComponentName
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.accessibility.Flags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.accessibility.AccessibilityShortcutController
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.ColorCorrectionTile
import com.android.systemui.qs.tiles.ColorInversionTile
import com.android.systemui.qs.tiles.OneHandedModeTile
import com.android.systemui.qs.tiles.ReduceBrightColorsTile
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class A11yShortcutAutoAddableListTest : SysuiTestCase() {

    private val factory =
        object : A11yShortcutAutoAddable.Factory {
            override fun create(
                spec: TileSpec,
                componentName: ComponentName
            ): A11yShortcutAutoAddable {
                return A11yShortcutAutoAddable(mock(), mock(), spec, componentName)
            }
        }

    @Test
    @DisableFlags(Flags.FLAG_A11Y_QS_SHORTCUT)
    fun getA11yShortcutAutoAddables_withA11yQsShortcutFlagOff_emptyResult() {
        val autoAddables = A11yShortcutAutoAddableList.getA11yShortcutAutoAddables(factory)

        assertThat(autoAddables).isEmpty()
    }

    @Test
    @EnableFlags(Flags.FLAG_A11Y_QS_SHORTCUT)
    fun getA11yShortcutAutoAddables_withA11yQsShortcutFlagOn_correctAutoAddables() {
        val expected =
            setOf(
                factory.create(
                    TileSpec.create(ColorCorrectionTile.TILE_SPEC),
                    AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME
                ),
                factory.create(
                    TileSpec.create(ColorInversionTile.TILE_SPEC),
                    AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME
                ),
                factory.create(
                    TileSpec.create(OneHandedModeTile.TILE_SPEC),
                    AccessibilityShortcutController.ONE_HANDED_COMPONENT_NAME
                ),
                factory.create(
                    TileSpec.create(ReduceBrightColorsTile.TILE_SPEC),
                    AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_COMPONENT_NAME
                ),
            )

        val autoAddables = A11yShortcutAutoAddableList.getA11yShortcutAutoAddables(factory)

        assertThat(autoAddables).isNotEmpty()
        assertThat(autoAddables).containsExactlyElementsIn(expected)
    }
}
