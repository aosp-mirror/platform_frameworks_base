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

import static android.text.Layout.HYPHENATION_FREQUENCY_NORMAL_FAST;

import android.app.ActionBar;
import android.graphics.text.LineBreakConfig;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.android.settingslib.widget.R;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

/**
 * A delegate that allows to use the collapsing toolbar layout in hosts that doesn't want/need to
 * extend from {@link CollapsingToolbarBaseActivity} or from {@link CollapsingToolbarBaseFragment}.
 */
public class CollapsingToolbarDelegate {

    /** Interface to be implemented by the host of the Collapsing Toolbar. */
    public interface HostCallback {
        /**
         * Called when a Toolbar should be set on the host.
         *
         * <p>If the host wants action bar to be modified, it should return it.
         */
        @Nullable
        ActionBar setActionBar(Toolbar toolbar);

        /** Sets a title on the host. */
        void setOuterTitle(CharSequence title);
    }

    private static final float TOOLBAR_LINE_SPACING_MULTIPLIER = 1.1f;

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
    @NonNull
    private final HostCallback mHostCallback;

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback) {
        mHostCallback = hostCallback;
    }

    /** Method to call that creates the root view of the collapsing toolbar. */
    @SuppressWarnings("RestrictTo")
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        final View view =
                inflater.inflate(R.layout.collapsing_toolbar_base_layout, container, false);
        if (view instanceof CoordinatorLayout) {
            mCoordinatorLayout = (CoordinatorLayout) view;
        }
        mCollapsingToolbarLayout = view.findViewById(R.id.collapsing_toolbar);
        mAppBarLayout = view.findViewById(R.id.app_bar);
        if (mCollapsingToolbarLayout != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mCollapsingToolbarLayout.setLineSpacingMultiplier(TOOLBAR_LINE_SPACING_MULTIPLIER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mCollapsingToolbarLayout.setHyphenationFrequency(HYPHENATION_FREQUENCY_NORMAL_FAST);
                mCollapsingToolbarLayout.setStaticLayoutBuilderConfigurer(builder ->
                        builder.setLineBreakConfig(
                                new LineBreakConfig.Builder()
                                        .setLineBreakWordStyle(
                                                LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                                        .build()));
            }
        }
        disableCollapsingToolbarLayoutScrollingBehavior();
        mToolbar = view.findViewById(R.id.action_bar);
        mContentFrameLayout = view.findViewById(R.id.content_frame);
        final ActionBar actionBar = mHostCallback.setActionBar(mToolbar);

        // Enable title and home button by default
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
        }
        return view;
    }

    /** Return an instance of CoordinatorLayout. */
    @Nullable
    public CoordinatorLayout getCoordinatorLayout() {
        return mCoordinatorLayout;
    }

    /** Sets the title on the collapsing layout, delegating to host if needed. */
    public void setTitle(CharSequence title) {
        if (mCollapsingToolbarLayout != null) {
            mCollapsingToolbarLayout.setTitle(title);
        } else {
            mHostCallback.setOuterTitle(title);
        }
    }

    /** Returns an instance of collapsing toolbar. */
    @Nullable
    public CollapsingToolbarLayout getCollapsingToolbarLayout() {
        return mCollapsingToolbarLayout;
    }

    /** Return the content frame layout. */
    @NonNull
    public FrameLayout getContentFrameLayout() {
        return mContentFrameLayout;
    }

    public Toolbar getToolbar() {
        return mToolbar;
    }

    /** Return an instance of app bar. */
    @Nullable
    public AppBarLayout getAppBarLayout() {
        return mAppBarLayout;
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
}
