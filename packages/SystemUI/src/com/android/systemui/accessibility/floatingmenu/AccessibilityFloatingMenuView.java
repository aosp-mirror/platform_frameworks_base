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

package com.android.systemui.accessibility.floatingmenu;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.DimenRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility floating menu is used for the actions of accessibility features, it's also the
 * action set.
 *
 * <p>The number of items would depend on strings key
 * {@link android.provider.Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS}.
 */
public class AccessibilityFloatingMenuView extends FrameLayout {
    private static final float DEFAULT_LOCATION_Y_PERCENTAGE = 0.8f;
    private static final int INDEX_MENU_ITEM = 0;
    private boolean mIsShowing;
    @SizeType
    private int mSizeType = SizeType.SMALL;
    private int mMargin;
    private int mPadding;
    private int mScreenHeight;
    private int mScreenWidth;
    private int mIconWidth;
    private int mIconHeight;
    private final RecyclerView mListView;
    private final AccessibilityTargetAdapter mAdapter;
    private final WindowManager.LayoutParams mLayoutParams;
    private final WindowManager mWindowManager;
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();

    @IntDef({
            SizeType.SMALL,
            SizeType.LARGE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SizeType {
        int SMALL = 0;
        int LARGE = 1;
    }

    @IntDef({
            ShapeType.CIRCLE,
            ShapeType.HALF_CIRCLE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ShapeType {
        int CIRCLE = 0;
        int HALF_CIRCLE = 1;
    }

    public AccessibilityFloatingMenuView(Context context) {
        this(context, new RecyclerView(context));
    }

    @VisibleForTesting
    AccessibilityFloatingMenuView(Context context,
            RecyclerView listView) {
        super(context);

        mListView = listView;
        mWindowManager = context.getSystemService(WindowManager.class);
        mLayoutParams = createDefaultLayoutParams();
        mAdapter = new AccessibilityTargetAdapter(mTargets);

        updateDimensions();
        initListView();

        final int uiMode = context.getResources().getConfiguration().uiMode;
        updateStrokeWith(uiMode);
    }

    void show() {
        if (isShowing()) {
            return;
        }

        mIsShowing = true;
        mWindowManager.addView(this, mLayoutParams);
    }

    void hide() {
        if (!isShowing()) {
            return;
        }

        mIsShowing = false;
        mWindowManager.removeView(this);
    }

    boolean isShowing() {
        return mIsShowing;
    }

    void onTargetsChanged(List<AccessibilityTarget> newTargets) {
        mTargets.clear();
        mTargets.addAll(newTargets);
        mAdapter.notifyDataSetChanged();

        updateRadiusWith(mSizeType, mTargets.size());
    }

    void setSizeType(@SizeType int newSizeType) {
        mSizeType = newSizeType;

        updateIconSizeWith(newSizeType);
        updateRadiusWith(newSizeType, mTargets.size());

        // When the icon sized changed, the menu size and location will be impacted.
        updateLocation();
    }

    void setShapeType(@ShapeType int newShapeType) {
        final boolean isCircleShape =
                newShapeType == ShapeType.CIRCLE;
        final float offset =
                isCircleShape
                        ? 0
                        : getLayoutWidth() / 2.0f;
        mListView.animate().translationX(offset);

        setOnTouchListener(
                isCircleShape
                        ? null
                        : (view, event) -> onTouched(event));
    }

    private boolean onTouched(MotionEvent event) {
        final int currentX = (int) event.getX();
        final int currentY = (int) event.getY();

        final int menuHalfWidth = getLayoutWidth() / 2;
        final Rect touchDelegateBounds =
                new Rect(mMargin, mMargin, mMargin + menuHalfWidth, mMargin + getLayoutHeight());
        if (touchDelegateBounds.contains(currentX, currentY)) {
            // In order to correspond to the correct item of list view.
            event.setLocation(currentX - mMargin, currentY - mMargin);
            return mListView.dispatchTouchEvent(event);
        }

        return false;
    }

    private void setRadius(float radius) {
        final float[] radii = new float[]{radius, radius, 0.0f, 0.0f, 0.0f, 0.0f, radius, radius};
        getMenuGradientDrawable().setCornerRadii(radii);
    }

    private void updateDimensions() {
        final Resources res = getResources();
        final DisplayMetrics dm = res.getDisplayMetrics();
        mScreenWidth = dm.widthPixels;
        mScreenHeight = dm.heightPixels;
        mMargin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_margin);
        mPadding =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_padding);
    }

    private void updateIconSizeWith(@SizeType int sizeType) {
        final Resources res = getResources();
        final int iconResId =
                sizeType == SizeType.SMALL
                        ? R.dimen.accessibility_floating_menu_small_width_height
                        : R.dimen.accessibility_floating_menu_large_width_height;
        mIconWidth = res.getDimensionPixelSize(iconResId);
        mIconHeight = mIconWidth;

        mAdapter.setIconWidthHeight(mIconWidth);
        mAdapter.notifyDataSetChanged();
    }

    private void initListView() {
        final Drawable listViewBackground =
                getContext().getDrawable(R.drawable.accessibility_floating_menu_background);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        mListView.setBackground(listViewBackground);
        mListView.setAdapter(mAdapter);
        mListView.setLayoutManager(layoutManager);
        updateListView();

        addView(mListView);
    }

    private void updateListView() {
        final int elevation =
                getResources().getDimensionPixelSize(R.dimen.accessibility_floating_menu_elevation);
        final LayoutParams layoutParams =
                new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.setMarginsRelative(mMargin, mMargin, /* end= */ 0, mMargin);
        mListView.setLayoutParams(layoutParams);
        mListView.setElevation(elevation);
    }

    private WindowManager.LayoutParams createDefaultLayoutParams() {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        params.gravity = Gravity.START | Gravity.TOP;
        params.x = dm.widthPixels;
        params.y = (int) (dm.heightPixels * DEFAULT_LOCATION_Y_PERCENTAGE);

        return params;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        updateDimensions();
        updateListView();
        updateIconSizeWith(mSizeType);
        updateColor();
        updateStrokeWith(newConfig.uiMode);
        updateLocation();
    }

    private LayerDrawable getMenuLayerDrawable() {
        return (LayerDrawable) mListView.getBackground();
    }

    private GradientDrawable getMenuGradientDrawable() {
        return (GradientDrawable) getMenuLayerDrawable().getDrawable(INDEX_MENU_ITEM);
    }

    /**
     * Updates the floating menu to be fixed at the side of the screen.
     */
    private void updateLocation() {
        mLayoutParams.x = mScreenWidth - mMargin - getLayoutWidth();
        mLayoutParams.y = (int) (mScreenHeight * DEFAULT_LOCATION_Y_PERCENTAGE);
        mWindowManager.updateViewLayout(this, mLayoutParams);
    }

    private void updateColor() {
        final int menuColorResId = R.color.accessibility_floating_menu_background;
        getMenuGradientDrawable().setColor(getResources().getColor(menuColorResId));
    }

    private void updateStrokeWith(int uiMode) {
        final Resources res = getResources();
        final boolean isNightMode =
                (uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;

        final int inset =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_inset);
        final int insetRight = isNightMode ? inset : 0;
        getMenuLayerDrawable().setLayerInset(INDEX_MENU_ITEM, 0, 0, insetRight, 0);

        final int width =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_width);
        final int strokeWidth = isNightMode ? width : 0;
        final int strokeColor = res.getColor(R.color.accessibility_floating_menu_stroke_dark);
        getMenuGradientDrawable().setStroke(strokeWidth, strokeColor);
    }

    private void updateRadiusWith(@SizeType int sizeType, int itemCount) {
        setRadius(getResources().getDimensionPixelSize(getRadiusResId(sizeType, itemCount)));
    }

    private @DimenRes int getRadiusResId(@SizeType int sizeType, int itemCount) {
        return sizeType == SizeType.SMALL
                ? getSmallSizeResIdWith(itemCount)
                : getLargeSizeResIdWith(itemCount);
    }

    private int getSmallSizeResIdWith(int itemCount) {
        return itemCount > 1
                ? R.dimen.accessibility_floating_menu_small_multiple_radius
                : R.dimen.accessibility_floating_menu_small_single_radius;
    }

    private int getLargeSizeResIdWith(int itemCount) {
        return itemCount > 1
                ? R.dimen.accessibility_floating_menu_large_multiple_radius
                : R.dimen.accessibility_floating_menu_large_single_radius;
    }

    private int getLayoutWidth() {
        return mPadding * 2 + mIconWidth;
    }

    private int getLayoutHeight() {
        return (mPadding + mIconHeight) * mTargets.size() + mPadding;
    }
}
