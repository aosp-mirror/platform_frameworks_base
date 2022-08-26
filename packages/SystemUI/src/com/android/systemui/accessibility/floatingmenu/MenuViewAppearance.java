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

package com.android.systemui.accessibility.floatingmenu;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import com.android.systemui.R;

/**
 * Provides the layout resources information of the {@link MenuView}.
 */
class MenuViewAppearance {
    private final Resources mRes;
    private int mTargetFeaturesSize;
    private int mSmallPadding;
    private int mSmallIconSize;
    private int mSmallSingleRadius;
    private int mSmallMultipleRadius;
    private int mElevation;
    private float[] mRadii;
    private Drawable mBackgroundDrawable;

    MenuViewAppearance(Context context) {
        mRes = context.getResources();

        update();
    }

    void update() {
        mSmallPadding =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_padding);
        mSmallIconSize =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_width_height);
        mSmallSingleRadius =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_single_radius);
        mSmallMultipleRadius = mRes.getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_small_multiple_radius);
        mRadii = createRadii(getMenuRadius(mTargetFeaturesSize));
        mElevation = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_elevation);
        final Drawable drawable =
                mRes.getDrawable(R.drawable.accessibility_floating_menu_background);
        mBackgroundDrawable = new InstantInsetLayerDrawable(new Drawable[]{drawable});
    }

    void setTargetFeaturesSize(int targetFeaturesSize) {
        mTargetFeaturesSize = targetFeaturesSize;

        mRadii = createRadii(getMenuRadius(targetFeaturesSize));
    }

    Drawable getMenuBackground() {
        return mBackgroundDrawable;
    }

    int getMenuElevation() {
        return mElevation;
    }

    int getMenuIconSize() {
        return mSmallIconSize;
    }

    int getMenuPadding() {
        return mSmallPadding;
    }

    float[] getMenuRadii() {
        return mRadii;
    }

    private int getMenuRadius(int itemCount) {
        return itemCount > 1 ? mSmallMultipleRadius : mSmallSingleRadius;
    }

    private static float[] createRadii(float radius) {
        return new float[]{0.0f, 0.0f, radius, radius, radius, radius, 0.0f, 0.0f};
    }
}
