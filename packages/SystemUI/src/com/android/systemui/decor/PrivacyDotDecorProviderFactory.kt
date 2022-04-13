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
import android.content.res.Resources
import android.view.DisplayCutout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Surface
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject

@SysUISingleton
class PrivacyDotDecorProviderFactory @Inject constructor(
    @Main private val res: Resources
) : DecorProviderFactory() {

    private val isPrivacyDotEnabled: Boolean
        get() = res.getBoolean(R.bool.config_enablePrivacyDot)

    override val hasProviders: Boolean
        get() = isPrivacyDotEnabled

    override fun onDisplayUniqueIdChanged(displayUniqueId: String?) {
        // Do nothing for privacy dot
    }

    override val providers: List<DecorProvider>
        get() {
            return if (hasProviders) {
                listOf(
                    PrivacyDotCornerDecorProviderImpl(
                        viewId = R.id.privacy_dot_top_left_container,
                        alignedBound1 = DisplayCutout.BOUNDS_POSITION_TOP,
                        alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                        layoutId = R.layout.privacy_dot_top_left),
                    PrivacyDotCornerDecorProviderImpl(
                        viewId = R.id.privacy_dot_top_right_container,
                        alignedBound1 = DisplayCutout.BOUNDS_POSITION_TOP,
                        alignedBound2 = DisplayCutout.BOUNDS_POSITION_RIGHT,
                        layoutId = R.layout.privacy_dot_top_right),
                    PrivacyDotCornerDecorProviderImpl(
                        viewId = R.id.privacy_dot_bottom_left_container,
                        alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                        alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                        layoutId = R.layout.privacy_dot_bottom_left),
                    PrivacyDotCornerDecorProviderImpl(
                        viewId = R.id.privacy_dot_bottom_right_container,
                        alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                        alignedBound2 = DisplayCutout.BOUNDS_POSITION_RIGHT,
                        layoutId = R.layout.privacy_dot_bottom_right)
                )
            } else {
                emptyList()
            }
        }
}

class PrivacyDotCornerDecorProviderImpl(
    override val viewId: Int,
    @DisplayCutout.BoundsPosition override val alignedBound1: Int,
    @DisplayCutout.BoundsPosition override val alignedBound2: Int,
    private val layoutId: Int
) : CornerDecorProvider() {

    override fun onReloadResAndMeasure(
        view: View,
        reloadToken: Int,
        rotation: Int,
        displayUniqueId: String?
    ) {
        // Do nothing here because it is handled inside PrivacyDotViewController
    }

    override fun inflateView(
        context: Context,
        parent: ViewGroup,
        @Surface.Rotation rotation: Int
    ): View {
        LayoutInflater.from(context).inflate(layoutId, parent, true)
        return parent.getChildAt(parent.childCount - 1 /* latest new added child */)
    }
}
