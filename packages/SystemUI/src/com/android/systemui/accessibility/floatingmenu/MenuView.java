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
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;

import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.accessibility.dialog.AccessibilityTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * The container view displays the accessibility features.
 */
@SuppressLint("ViewConstructor")
class MenuView extends FrameLayout {
    private static final int INDEX_MENU_ITEM = 0;
    private final List<AccessibilityTarget> mTargetFeatures = new ArrayList<>();
    private final AccessibilityTargetAdapter mAdapter;
    private final MenuViewModel mMenuViewModel;
    private final RecyclerView mTargetFeaturesView;
    private final Observer<List<AccessibilityTarget>> mTargetFeaturesObserver =
            this::onTargetFeaturesChanged;
    private final MenuViewAppearance mMenuViewAppearance;

    MenuView(Context context, MenuViewModel menuViewModel, MenuViewAppearance menuViewAppearance) {
        super(context);

        mMenuViewModel = menuViewModel;
        mMenuViewAppearance = menuViewAppearance;
        mAdapter = new AccessibilityTargetAdapter(mTargetFeatures);
        mTargetFeaturesView = new RecyclerView(context);
        mTargetFeaturesView.setAdapter(mAdapter);
        mTargetFeaturesView.setLayoutManager(new LinearLayoutManager(context));
        setLayoutParams(new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        loadLayoutResources();

        addView(mTargetFeaturesView);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        loadLayoutResources();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onItemSizeChanged() {
        mAdapter.setItemPadding(mMenuViewAppearance.getMenuPadding());
        mAdapter.setIconWidthHeight(mMenuViewAppearance.getMenuIconSize());
        mAdapter.notifyDataSetChanged();
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

    @SuppressLint("NotifyDataSetChanged")
    private void onTargetFeaturesChanged(List<AccessibilityTarget> newTargetFeatures) {
        // TODO(b/252756133): Should update specific item instead of the whole list
        mTargetFeatures.clear();
        mTargetFeatures.addAll(newTargetFeatures);
        mMenuViewAppearance.setTargetFeaturesSize(mTargetFeatures.size());
        mAdapter.notifyDataSetChanged();

        onEdgeChanged();
    }

    void show() {
        mMenuViewModel.getTargetFeaturesData().observeForever(mTargetFeaturesObserver);
        setVisibility(VISIBLE);
        mMenuViewModel.registerContentObservers();
    }

    void hide() {
        setVisibility(GONE);
        mMenuViewModel.getTargetFeaturesData().removeObserver(mTargetFeaturesObserver);
        mMenuViewModel.unregisterContentObservers();
    }

    void loadLayoutResources() {
        mMenuViewAppearance.update();

        setBackground(mMenuViewAppearance.getMenuBackground());
        setElevation(mMenuViewAppearance.getMenuElevation());
        onItemSizeChanged();
        onEdgeChanged();
    }

    private InstantInsetLayerDrawable getContainerViewInsetLayer() {
        return (InstantInsetLayerDrawable) getBackground();
    }

    private GradientDrawable getContainerViewGradient() {
        return (GradientDrawable) getContainerViewInsetLayer().getDrawable(INDEX_MENU_ITEM);
    }
}
