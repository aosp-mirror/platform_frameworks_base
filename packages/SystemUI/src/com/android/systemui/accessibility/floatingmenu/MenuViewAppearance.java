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

import static com.android.systemui.accessibility.floatingmenu.MenuViewAppearance.MenuSizeType.SMALL;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import androidx.annotation.DimenRes;

import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides the layout resources information of the {@link MenuView}.
 */
class MenuViewAppearance {
    private final Resources mRes;
    private int mTargetFeaturesSize;
    private int mSizeType;
    private int mSmallPadding;
    private int mLargePadding;
    private int mSmallIconSize;
    private int mLargeIconSize;
    private int mSmallSingleRadius;
    private int mSmallMultipleRadius;
    private int mLargeSingleRadius;
    private int mLargeMultipleRadius;
    private int mStrokeWidth;
    private int mStrokeColor;
    private int mInset;
    private int mElevation;
    private float[] mRadii;
    private Drawable mBackgroundDrawable;

    @IntDef({
            SMALL,
            MenuSizeType.LARGE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MenuSizeType {
        int SMALL = 0;
        int LARGE = 1;
    }

    MenuViewAppearance(Context context) {
        mRes = context.getResources();

        update();
    }

    void update() {
        mSmallPadding =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_padding);
        mLargePadding =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_large_padding);
        mSmallIconSize =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_width_height);
        mLargeIconSize =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_large_width_height);
        mSmallSingleRadius =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_small_single_radius);
        mSmallMultipleRadius = mRes.getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_small_multiple_radius);
        mRadii = createRadii(getMenuRadius(mTargetFeaturesSize));
        mLargeSingleRadius =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_large_single_radius);
        mLargeMultipleRadius = mRes.getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_large_multiple_radius);
        mStrokeWidth = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_width);
        mStrokeColor = mRes.getColor(R.color.accessibility_floating_menu_stroke_dark);
        mInset = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_inset);
        mElevation = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_elevation);
        final Drawable drawable =
                mRes.getDrawable(R.drawable.accessibility_floating_menu_background);
        mBackgroundDrawable = new InstantInsetLayerDrawable(new Drawable[]{drawable});
    }

    void setSizeType(int sizeType) {
        mSizeType = sizeType;

        mRadii = createRadii(getMenuRadius(mTargetFeaturesSize));
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

    int getMenuHeight() {
        return calculateActualMenuHeight();
    }

    int getMenuIconSize() {
        return mSizeType == SMALL ? mSmallIconSize : mLargeIconSize;
    }

    int getMenuPadding() {
        return mSizeType == SMALL ? mSmallPadding : mLargePadding;
    }

    int[] getMenuInsets() {
        return new int[]{mInset, 0, 0, 0};
    }

    int getMenuStrokeWidth() {
        return mStrokeWidth;
    }

    int getMenuStrokeColor() {
        return mStrokeColor;
    }

    float[] getMenuRadii() {
        return mRadii;
    }

    private int getMenuRadius(int itemCount) {
        return mSizeType == SMALL ? getSmallSize(itemCount) : getLargeSize(itemCount);
    }

    @DimenRes
    private int getSmallSize(int itemCount) {
        return itemCount > 1 ? mSmallMultipleRadius : mSmallSingleRadius;
    }

    @DimenRes
    private int getLargeSize(int itemCount) {
        return itemCount > 1 ? mLargeMultipleRadius : mLargeSingleRadius;
    }

    private static float[] createRadii(float radius) {
        return new float[]{0.0f, 0.0f, radius, radius, radius, radius, 0.0f, 0.0f};
    }

    private int calculateActualMenuHeight() {
        final int menuPadding = getMenuPadding();

        return (menuPadding + getMenuIconSize()) * mTargetFeaturesSize + menuPadding;
    }
}
