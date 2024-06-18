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

package com.android.systemui.fold.ui.helper

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class FoldPostureTest : SysuiTestCase() {

    @Test
    fun foldPosture_whenNull_returnsFolded() {
        assertThat(foldPostureInternal(null)).isEqualTo(FoldPosture.Folded)
    }

    @Test
    fun foldPosture_whenHalfOpenHorizontally_returnsTabletop() {
        assertThat(
                foldPostureInternal(
                    createWindowLayoutInfo(
                        state = FoldingFeature.State.HALF_OPENED,
                        orientation = FoldingFeature.Orientation.HORIZONTAL,
                    )
                )
            )
            .isEqualTo(FoldPosture.Tabletop)
    }

    @Test
    fun foldPosture_whenHalfOpenVertically_returnsBook() {
        assertThat(
                foldPostureInternal(
                    createWindowLayoutInfo(
                        state = FoldingFeature.State.HALF_OPENED,
                        orientation = FoldingFeature.Orientation.VERTICAL,
                    )
                )
            )
            .isEqualTo(FoldPosture.Book)
    }

    @Test
    fun foldPosture_whenFlatAndNotSeparating_returnsFullyUnfolded() {
        assertThat(
                foldPostureInternal(
                    createWindowLayoutInfo(
                        state = FoldingFeature.State.FLAT,
                        orientation = FoldingFeature.Orientation.HORIZONTAL,
                        isSeparating = false,
                    )
                )
            )
            .isEqualTo(FoldPosture.FullyUnfolded)
    }

    @Test
    fun foldPosture_whenFlatAndSeparatingHorizontally_returnsTabletop() {
        assertThat(
                foldPostureInternal(
                    createWindowLayoutInfo(
                        state = FoldingFeature.State.FLAT,
                        isSeparating = true,
                        orientation = FoldingFeature.Orientation.HORIZONTAL,
                    )
                )
            )
            .isEqualTo(FoldPosture.Tabletop)
    }

    @Test
    fun foldPosture_whenFlatAndSeparatingVertically_returnsBook() {
        assertThat(
                foldPostureInternal(
                    createWindowLayoutInfo(
                        state = FoldingFeature.State.FLAT,
                        isSeparating = true,
                        orientation = FoldingFeature.Orientation.VERTICAL,
                    )
                )
            )
            .isEqualTo(FoldPosture.Book)
    }

    private fun createWindowLayoutInfo(
        state: FoldingFeature.State,
        orientation: FoldingFeature.Orientation = FoldingFeature.Orientation.VERTICAL,
        isSeparating: Boolean = false,
        occlusionType: FoldingFeature.OcclusionType = FoldingFeature.OcclusionType.NONE,
    ): WindowLayoutInfo {
        return WindowLayoutInfo(
            listOf(
                object : FoldingFeature {
                    override val bounds: Rect = Rect(0, 0, 100, 100)
                    override val isSeparating: Boolean = isSeparating
                    override val occlusionType: FoldingFeature.OcclusionType = occlusionType
                    override val orientation: FoldingFeature.Orientation = orientation
                    override val state: FoldingFeature.State = state
                }
            )
        )
    }
}
