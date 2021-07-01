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

package com.android.settingslib.collapsingtoolbar;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

/**
 * A base fragment that has a collapsing toolbar layout for enabling the collapsing toolbar design.
 */
public abstract class CollapsingToolbarBaseFragment extends Fragment implements
        AppBarLayout.OnOffsetChangedListener {

    private static final int TOOLBAR_MAX_LINE_NUMBER = 2;
    private static final int FULLY_EXPANDED_OFFSET = 0;
    private static final String KEY_IS_TOOLBAR_COLLAPSED = "is_toolbar_collapsed";

    @Nullable
    private CoordinatorLayout mCoordinatorLayout;
    @Nullable
    private CollapsingToolbarLayout mCollapsingToolbarLayout;
    @Nullable
    private AppBarLayout mAppBarLayout;
    @NonNull
    private Toolbar mToolbar;
    @NonNull
    private FrameLayout mContentFrameLayout;
    private boolean mIsToolbarCollapsed;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.collapsing_toolbar_base_layout, container,
                false);
        mCoordinatorLayout = view.findViewById(R.id.content_parent);
        mCollapsingToolbarLayout = view.findViewById(R.id.collapsing_toolbar);
        mAppBarLayout = view.findViewById(R.id.app_bar);
        if (mAppBarLayout != null) {
            mAppBarLayout.addOnOffsetChangedListener(this);
        }
        if (savedInstanceState != null) {
            mIsToolbarCollapsed = savedInstanceState.getBoolean(KEY_IS_TOOLBAR_COLLAPSED);
        }
        initCollapsingToolbar();
        disableCollapsingToolbarLayoutScrollingBehavior();
        mToolbar = view.findViewById(R.id.action_bar);
        mContentFrameLayout = view.findViewById(R.id.content_frame);
        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        requireActivity().setActionBar(mToolbar);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (getActivity().isChangingConfigurations()) {
            outState.putBoolean(KEY_IS_TOOLBAR_COLLAPSED, mIsToolbarCollapsed);
        }
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int offset) {
        if (offset == FULLY_EXPANDED_OFFSET) {
            mIsToolbarCollapsed = false;
        } else {
            mIsToolbarCollapsed = true;
        }
    }

    /**
     * Return an instance of CoordinatorLayout.
     */
    @Nullable
    public CoordinatorLayout getCoordinatorLayout() {
        return mCoordinatorLayout;
    }

    /**
     * Return an instance of app bar.
     */
    @Nullable
    public AppBarLayout getAppBarLayout() {
        return mAppBarLayout;
    }

    /**
     * Return the collapsing toolbar layout.
     */
    @Nullable
    public CollapsingToolbarLayout getCollapsingToolbarLayout() {
        return mCollapsingToolbarLayout;
    }

    /**
     * Return the content frame layout.
     */
    @NonNull
    public FrameLayout getContentFrameLayout() {
        return mContentFrameLayout;
    }

    private void disableCollapsingToolbarLayoutScrollingBehavior() {
        if (mAppBarLayout == null) {
            return;
        }
        final CoordinatorLayout.LayoutParams params =
                (CoordinatorLayout.LayoutParams) mAppBarLayout.getLayoutParams();
        final AppBarLayout.Behavior behavior = new AppBarLayout.Behavior();
        behavior.setDragCallback(
                new AppBarLayout.Behavior.DragCallback() {
                    @Override
                    public boolean canDrag(@NonNull AppBarLayout appBarLayout) {
                        return false;
                    }
                });
        params.setBehavior(behavior);
    }

    @SuppressWarnings("RestrictTo")
    private void initCollapsingToolbar() {
        if (mCollapsingToolbarLayout == null || mAppBarLayout == null) {
            return;
        }
        mCollapsingToolbarLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                v.removeOnLayoutChangeListener(this);
                if (mIsToolbarCollapsed) {
                    return;
                }
                final int count = mCollapsingToolbarLayout.getLineCount();
                if (count > TOOLBAR_MAX_LINE_NUMBER) {
                    final ViewGroup.LayoutParams lp = mCollapsingToolbarLayout.getLayoutParams();
                    lp.height = getResources()
                            .getDimensionPixelSize(R.dimen.toolbar_three_lines_height);
                    mCollapsingToolbarLayout.setScrimVisibleHeightTrigger(
                            getResources().getDimensionPixelSize(
                                    R.dimen.scrim_visible_height_trigger_three_lines));
                    mCollapsingToolbarLayout.setLayoutParams(lp);
                } else if (count == TOOLBAR_MAX_LINE_NUMBER) {
                    final ViewGroup.LayoutParams lp = mCollapsingToolbarLayout.getLayoutParams();
                    lp.height = getResources()
                            .getDimensionPixelSize(R.dimen.toolbar_two_lines_height);
                    mCollapsingToolbarLayout.setScrimVisibleHeightTrigger(
                            getResources().getDimensionPixelSize(
                                    R.dimen.scrim_visible_height_trigger_two_lines));
                    mCollapsingToolbarLayout.setLayoutParams(lp);
                }
            }
        });
    }
}
