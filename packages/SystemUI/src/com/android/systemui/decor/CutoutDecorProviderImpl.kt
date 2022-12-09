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

import android.content.Context
import android.view.DisplayCutout
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import com.android.systemui.R
import com.android.systemui.ScreenDecorations.DisplayCutoutView

class CutoutDecorProviderImpl(
    @DisplayCutout.BoundsPosition override val alignedBound: Int
) : BoundDecorProvider() {

    override val viewId: Int = when (alignedBound) {
        DisplayCutout.BOUNDS_POSITION_TOP -> R.id.display_cutout
        DisplayCutout.BOUNDS_POSITION_LEFT -> R.id.display_cutout_left
        DisplayCutout.BOUNDS_POSITION_RIGHT -> R.id.display_cutout_right
        else -> R.id.display_cutout_bottom
    }

    override fun inflateView(
        context: Context,
        parent: ViewGroup,
        @Surface.Rotation rotation: Int,
        tintColor: Int
    ): View {
        return DisplayCutoutView(context, alignedBound).also { view ->
            view.id = viewId
            view.setColor(tintColor)
            parent.addView(view)
            view.updateRotation(rotation)
        }
    }

    override fun onReloadResAndMeasure(
        view: View,
        reloadToken: Int,
        @Surface.Rotation rotation: Int,
        tintColor: Int,
        displayUniqueId: String?
    ) {
        (view as? DisplayCutoutView)?.let { cutoutView ->
            cutoutView.setColor(tintColor)
            cutoutView.updateRotation(rotation)
            cutoutView.onDisplayChanged(displayUniqueId)
        }
    }
}
