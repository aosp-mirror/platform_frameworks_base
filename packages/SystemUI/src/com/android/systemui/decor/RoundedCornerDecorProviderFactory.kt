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

package com.android.systemui.decor

import android.view.DisplayCutout
import com.android.systemui.R

class RoundedCornerDecorProviderFactory(
    private val roundedCornerResDelegate: RoundedCornerResDelegate
) : DecorProviderFactory() {

    override val hasProviders: Boolean
        get() = roundedCornerResDelegate.run {
            hasTop || hasBottom
        }

    override fun onDisplayUniqueIdChanged(displayUniqueId: String?) {
        roundedCornerResDelegate.updateDisplayUniqueId(displayUniqueId, null)
    }

    override val providers: List<DecorProvider>
    get() {
        val hasTop = roundedCornerResDelegate.hasTop
        val hasBottom = roundedCornerResDelegate.hasBottom
        return when {
            hasTop && hasBottom -> listOf(
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_top_left,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_TOP,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                    roundedCornerResDelegate = roundedCornerResDelegate),
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_top_right,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_TOP,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_RIGHT,
                    roundedCornerResDelegate = roundedCornerResDelegate),
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_bottom_left,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                    roundedCornerResDelegate = roundedCornerResDelegate),
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_bottom_right,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_RIGHT,
                    roundedCornerResDelegate = roundedCornerResDelegate)
            )
            hasTop -> listOf(
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_top_left,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_TOP,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                    roundedCornerResDelegate = roundedCornerResDelegate),
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_top_right,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_TOP,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_RIGHT,
                    roundedCornerResDelegate = roundedCornerResDelegate)
            )
            hasBottom -> listOf(
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_bottom_left,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                    roundedCornerResDelegate = roundedCornerResDelegate),
                RoundedCornerDecorProviderImpl(
                    viewId = R.id.rounded_corner_bottom_right,
                    alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                    alignedBound2 = DisplayCutout.BOUNDS_POSITION_RIGHT,
                    roundedCornerResDelegate = roundedCornerResDelegate)
            )
            else -> emptyList()
        }
    }
}