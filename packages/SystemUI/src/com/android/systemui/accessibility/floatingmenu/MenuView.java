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

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.accessibility.dialog.AccessibilityTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The container view displays the accessibility features.
 */
@SuppressLint("ViewConstructor")
class MenuView extends FrameLayout implements
        ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final int INDEX_MENU_ITEM = 0;
    private final List<AccessibilityTarget> mTargetFeatures = new ArrayList<>();
    private final AccessibilityTargetAdapter mAdapter;
    private final MenuViewModel mMenuViewModel;
    private final MenuAnimationController mMenuAnimationController;
    private final Rect mBoundsInParent = new Rect();
    private final RecyclerView mTargetFeaturesView;
    private final ViewTreeObserver.OnDrawListener mSystemGestureExcludeUpdater =
            this::updateSystemGestureExcludeRects;
    private final Observer<Position> mPercentagePositionObserver = this::onPercentagePosition;
    private final Observer<Integer> mSizeTypeObserver = this::onSizeTypeChanged;
    private final Observer<List<AccessibilityTarget>> mTargetFeaturesObserver =
            this::onTargetFeaturesChanged;
    private final MenuViewAppearance mMenuViewAppearance;

    MenuView(Context context, MenuViewModel menuViewModel, MenuViewAppearance menuViewAppearance) {
        super(context);

        mMenuViewModel = menuViewModel;
        mMenuViewAppearance = menuViewAppearance;
        mMenuAnimationController = new MenuAnimationController(this);
        mAdapter = new AccessibilityTargetAdapter(mTargetFeatures);
        mTargetFeaturesView = new RecyclerView(context);
        mTargetFeaturesView.setAdapter(mAdapter);
        mTargetFeaturesView.setLayoutManager(new LinearLayoutManager(context));
        setLayoutParams(new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        // Avoid drawing out of bounds of the parent view
        setClipToOutline(true);

        loadLayoutResources();

        addView(mTargetFeaturesView);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        inoutInfo.touchableRegion.set(mBoundsInParent);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        loadLayoutResources();

        mTargetFeaturesView.setOverScrollMode(mMenuViewAppearance.getMenuScrollMode());
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onItemSizeChanged() {
        mAdapter.setItemPadding(mMenuViewAppearance.getMenuPadding());
        mAdapter.setIconWidthHeight(mMenuViewAppearance.getMenuIconSize());
        mAdapter.notifyDataSetChanged();
    }

    private void onSizeChanged() {
        mBoundsInParent.set(mBoundsInParent.left, mBoundsInParent.top,
                mBoundsInParent.left + mMenuViewAppearance.getMenuWidth(),
                mBoundsInParent.top + mMenuViewAppearance.getMenuHeight());

        final FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        layoutParams.height = mMenuViewAppearance.getMenuHeight();
        setLayoutParams(layoutParams);
    }

    private void onEdgeChanged() {
        final int[] insets = mMenuViewAppearance.getMenuInsets();
        getContainerViewInsetLayer().setLayerInset(INDEX_MENU_ITEM, insets[0], insets[1], insets[2],
                insets[3]);

        final GradientDrawable gradientDrawable = getContainerViewGradient();
        gradientDrawable.setCornerRadii(mMenuViewAppearance.getMenuRadii());
        gradientDrawable.setStroke(mMenuViewAppearance.getMenuStrokeWidth(),
                mMenuViewAppearance.getMenuStrokeColor());
    }

    private void onPercentagePosition(Position percentagePosition) {
        mMenuViewAppearance.setPercentagePosition(percentagePosition);

        onPositionChanged();
    }

    private void onPositionChanged() {
        final PointF position = mMenuViewAppearance.getMenuPosition();
        mMenuAnimationController.moveToPosition(position);
        onBoundsInParentChanged((int) position.x, (int) position.y);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onSizeTypeChanged(int newSizeType) {
        mMenuViewAppearance.setSizeType(newSizeType);

        mAdapter.setItemPadding(mMenuViewAppearance.getMenuPadding());
        mAdapter.setIconWidthHeight(mMenuViewAppearance.getMenuIconSize());
        mAdapter.notifyDataSetChanged();

        onSizeChanged();
        onEdgeChanged();
        onPositionChanged();
    }

    private void onTargetFeaturesChanged(List<AccessibilityTarget> newTargetFeatures) {
        // TODO(b/252756133): Should update specific item instead of the whole list
        mTargetFeatures.clear();
        mTargetFeatures.addAll(newTargetFeatures);
        mMenuViewAppearance.setTargetFeaturesSize(mTargetFeatures.size());
        mTargetFeaturesView.setOverScrollMode(mMenuViewAppearance.getMenuScrollMode());
        mAdapter.notifyDataSetChanged();

        onSizeChanged();
        onEdgeChanged();
        onPositionChanged();
    }

    void show() {
        mMenuViewModel.getPercentagePositionData().observeForever(mPercentagePositionObserver);
        mMenuViewModel.getTargetFeaturesData().observeForever(mTargetFeaturesObserver);
        mMenuViewModel.getSizeTypeData().observeForever(mSizeTypeObserver);
        setVisibility(VISIBLE);
        mMenuViewModel.registerContentObservers();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        getViewTreeObserver().addOnDrawListener(mSystemGestureExcludeUpdater);
    }

    void hide() {
        setVisibility(GONE);
        mBoundsInParent.setEmpty();
        mMenuViewModel.getPercentagePositionData().removeObserver(mPercentagePositionObserver);
        mMenuViewModel.getTargetFeaturesData().removeObserver(mTargetFeaturesObserver);
        mMenuViewModel.getSizeTypeData().removeObserver(mSizeTypeObserver);
        mMenuViewModel.unregisterContentObservers();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        getViewTreeObserver().removeOnDrawListener(mSystemGestureExcludeUpdater);
    }

    void onBoundsInParentChanged(int newLeft, int newTop) {
        mBoundsInParent.offsetTo(newLeft, newTop);
    }

    void loadLayoutResources() {
        mMenuViewAppearance.update();

        setBackground(mMenuViewAppearance.getMenuBackground());
        setElevation(mMenuViewAppearance.getMenuElevation());
        onItemSizeChanged();
        onSizeChanged();
        onEdgeChanged();
        onPositionChanged();
    }

    private InstantInsetLayerDrawable getContainerViewInsetLayer() {
        return (InstantInsetLayerDrawable) getBackground();
    }

    private GradientDrawable getContainerViewGradient() {
        return (GradientDrawable) getContainerViewInsetLayer().getDrawable(INDEX_MENU_ITEM);
    }

    private void updateSystemGestureExcludeRects() {
        final ViewGroup parentView = (ViewGroup) getParent();
        parentView.setSystemGestureExclusionRects(Collections.singletonList(mBoundsInParent));
    }
}
