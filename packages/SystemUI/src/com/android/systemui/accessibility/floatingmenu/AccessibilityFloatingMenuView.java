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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.DimenRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.R;

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
    private RecyclerView mListView;
    private final WindowManager.LayoutParams mLayoutParams;
    private final WindowManager mWindowManager;
    private final AccessibilityTargetAdapter mAdapter;
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();

    public AccessibilityFloatingMenuView(Context context) {
        super(context);

        mWindowManager = context.getSystemService(WindowManager.class);
        mLayoutParams = createDefaultLayoutParams();
        mAdapter = new AccessibilityTargetAdapter(mTargets);

        initListView(context, mAdapter);
        updateStroke();
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

        final boolean hasMoreItems = mAdapter.getItemCount() > 1;
        final int resId = hasMoreItems
                ? R.dimen.accessibility_floating_menu_multiple_radius
                : R.dimen.accessibility_floating_menu_single_radius;
        setRadius(resId);
    }

    private void setRadius(@DimenRes int radiusResId) {
        final float radius = getResources().getDimension(radiusResId);
        final float[] radii = new float[]{radius, radius, 0.0f, 0.0f, 0.0f, 0.0f, radius, radius};
        getMenuGradientDrawable().setCornerRadii(radii);
    }

    private void initListView(Context context, AccessibilityTargetAdapter adapter) {
        final Resources res = context.getResources();
        final int margin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_margin);
        final int elevation =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_elevation);
        final Drawable listViewBackground =
                context.getDrawable(R.drawable.accessibility_floating_menu_background);

        mListView = new RecyclerView(context);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        final LayoutParams layoutParams =
                new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.setMarginsRelative(margin, margin, /* end= */ 0, margin);
        mListView.setLayoutParams(layoutParams);
        mListView.setElevation(elevation);
        mListView.setBackground(listViewBackground);
        mListView.setAdapter(adapter);
        mListView.setLayoutManager(layoutManager);

        addView(mListView);
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

        updateLocation();
        updateColor();
        updateStroke();
    }

    private LayerDrawable getMenuLayerDrawable() {
        return (LayerDrawable) mListView.getBackground();
    }

    private GradientDrawable getMenuGradientDrawable() {
        return (GradientDrawable) getMenuLayerDrawable().getDrawable(INDEX_MENU_ITEM);
    }

    private void updateLocation() {
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        mLayoutParams.x = dm.widthPixels;
        mLayoutParams.y = (int) (dm.heightPixels * DEFAULT_LOCATION_Y_PERCENTAGE);
        mWindowManager.updateViewLayout(this, mLayoutParams);
    }

    private void updateColor() {
        final int menuColorResId = R.color.accessibility_floating_menu_background;
        getMenuGradientDrawable().setColor(getResources().getColor(menuColorResId));
    }

    private void updateStroke() {
        final Resources res = getResources();
        final boolean isNightMode =
                (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
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
}
