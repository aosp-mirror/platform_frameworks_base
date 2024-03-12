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

import static android.view.View.OVER_SCROLL_ALWAYS;
import static android.view.View.OVER_SCROLL_NEVER;

import static com.android.systemui.accessibility.floatingmenu.MenuViewAppearance.MenuSizeType.SMALL;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.DimenRes;

import com.android.systemui.res.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides the layout resources information of the {@link MenuView}.
 */
class MenuViewAppearance {
    private final WindowManager mWindowManager;
    private final Resources mRes;
    private final Position mPercentagePosition = new Position(/* percentageX= */
            0f, /* percentageY= */ 0f);
    private boolean mIsImeShowing;
    // Avoid the menu view overlapping on the primary action button under the bottom as possible.
    private int mImeShiftingSpace;
    private int mTargetFeaturesSize;
    private int mSizeType;
    private int mMargin;
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
    private float mImeTop;
    private float[] mRadii;
    private Drawable mBackgroundDrawable;
    private String mContentDescription;

    @IntDef({
            SMALL,
            MenuSizeType.LARGE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MenuSizeType {
        int SMALL = 0;
        int LARGE = 1;
    }

    MenuViewAppearance(Context context, WindowManager windowManager) {
        mWindowManager = windowManager;
        mRes = context.getResources();

        update();
    }

    void update() {
        mMargin = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_margin);
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
        mRadii = createRadii(isMenuOnLeftSide(), getMenuRadius(mTargetFeaturesSize));
        mLargeSingleRadius =
                mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_large_single_radius);
        mLargeMultipleRadius = mRes.getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_large_multiple_radius);
        mStrokeWidth = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_width);
        mStrokeColor = mRes.getColor(R.color.accessibility_floating_menu_stroke_dark);
        mInset = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_inset);
        mElevation = mRes.getDimensionPixelSize(R.dimen.accessibility_floating_menu_elevation);
        mImeShiftingSpace = mRes.getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_ime_shifting_space);
        final Drawable drawable =
                mRes.getDrawable(R.drawable.accessibility_floating_menu_background);
        mBackgroundDrawable = new InstantInsetLayerDrawable(new Drawable[]{drawable});
        mContentDescription = mRes.getString(
                com.android.internal.R.string.accessibility_select_shortcut_menu_title);
    }

    void setSizeType(int sizeType) {
        mSizeType = sizeType;

        mRadii = createRadii(isMenuOnLeftSide(), getMenuRadius(mTargetFeaturesSize));
    }

    void setTargetFeaturesSize(int targetFeaturesSize) {
        mTargetFeaturesSize = targetFeaturesSize;

        mRadii = createRadii(isMenuOnLeftSide(), getMenuRadius(targetFeaturesSize));
    }

    void setPercentagePosition(Position percentagePosition) {
        mPercentagePosition.update(percentagePosition);

        mRadii = createRadii(isMenuOnLeftSide(), getMenuRadius(mTargetFeaturesSize));
    }

    void onImeVisibilityChanged(boolean imeShowing, float imeTop) {
        mIsImeShowing = imeShowing;
        mImeTop = imeTop;
    }

    Rect getMenuDraggableBounds() {
        return getMenuDraggableBoundsWith(/* includeIme= */ true);
    }

    Rect getMenuDraggableBoundsExcludeIme() {
        return getMenuDraggableBoundsWith(/* includeIme= */ false);
    }

    private Rect getMenuDraggableBoundsWith(boolean includeIme) {
        final int margin = getMenuMargin();
        final Rect draggableBounds = new Rect(getWindowAvailableBounds());

        draggableBounds.top += margin;
        draggableBounds.right -= getMenuWidth();

        if (includeIme && mIsImeShowing) {
            final int imeHeight = (int) (draggableBounds.bottom - mImeTop);
            draggableBounds.bottom -= (imeHeight + mImeShiftingSpace);
        }
        draggableBounds.bottom -= (calculateActualMenuHeight() + margin);
        draggableBounds.bottom = Math.max(draggableBounds.top, draggableBounds.bottom);

        return draggableBounds;
    }

    PointF getMenuPosition() {
        final Rect draggableBounds = getMenuDraggableBoundsExcludeIme();
        final float x = draggableBounds.left
                + draggableBounds.width() * mPercentagePosition.getPercentageX();

        float y = draggableBounds.top
                + draggableBounds.height() * mPercentagePosition.getPercentageY();

        // If the bottom of the menu view and overlap on the ime, its position y will be
        // overridden with new y.
        final float menuBottom = y + getMenuHeight() + mMargin;
        if (mIsImeShowing && (menuBottom >= mImeTop)) {
            y = Math.max(draggableBounds.top,
                    mImeTop - getMenuHeight() - mMargin - mImeShiftingSpace);
        }

        return new PointF(x, y);
    }

    String getContentDescription() {
        return mContentDescription;
    }

    Drawable getMenuBackground() {
        return mBackgroundDrawable;
    }

    int getMenuElevation() {
        return mElevation;
    }

    int getMenuWidth() {
        return getMenuPadding() * 2 + getMenuIconSize();
    }

    int getMenuHeight() {
        return Math.min(getWindowAvailableBounds().height() - mMargin * 2,
                calculateActualMenuHeight());
    }

    int getMenuIconSize() {
        return mSizeType == SMALL ? mSmallIconSize : mLargeIconSize;
    }

    private int getMenuMargin() {
        return mMargin;
    }

    int getMenuPadding() {
        return mSizeType == SMALL ? mSmallPadding : mLargePadding;
    }

    int[] getMenuInsets() {
        final int left = isMenuOnLeftSide() ? mInset : 0;
        final int right = isMenuOnLeftSide() ? 0 : mInset;

        return new int[]{left, 0, right, 0};
    }

    int[] getMenuMovingStateInsets() {
        return new int[]{0, 0, 0, 0};
    }

    float[] getMenuMovingStateRadii() {
        final float radius = getMenuRadius(mTargetFeaturesSize);
        return new float[]{radius, radius, radius, radius, radius, radius, radius, radius};
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

    int getMenuScrollMode() {
        return hasExceededMaxWindowHeight() ? OVER_SCROLL_ALWAYS : OVER_SCROLL_NEVER;
    }

    private boolean hasExceededMaxWindowHeight() {
        return calculateActualMenuHeight() > getWindowAvailableBounds().height();
    }

    @DimenRes
    private int getSmallSize(int itemCount) {
        return itemCount > 1 ? mSmallMultipleRadius : mSmallSingleRadius;
    }

    @DimenRes
    private int getLargeSize(int itemCount) {
        return itemCount > 1 ? mLargeMultipleRadius : mLargeSingleRadius;
    }

    private static float[] createRadii(boolean isMenuOnLeftSide, float radius) {
        return isMenuOnLeftSide
                ? new float[]{0.0f, 0.0f, radius, radius, radius, radius, 0.0f, 0.0f}
                : new float[]{radius, radius, 0.0f, 0.0f, 0.0f, 0.0f, radius, radius};
    }

    public Rect getWindowAvailableBounds() {
        final WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        final WindowInsets windowInsets = windowMetrics.getWindowInsets();
        final Insets insets = windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());

        final Rect bounds = new Rect(windowMetrics.getBounds());
        bounds.left += insets.left;
        bounds.right -= insets.right;
        bounds.top += insets.top;
        bounds.bottom -= insets.bottom;

        return bounds;
    }

    boolean isMenuOnLeftSide() {
        return mPercentagePosition.getPercentageX() < 0.5f;
    }

    private int calculateActualMenuHeight() {
        final int menuPadding = getMenuPadding();

        return (menuPadding + getMenuIconSize()) * mTargetFeaturesSize + menuPadding;
    }
}
