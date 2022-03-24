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

package com.android.settingslib.collapsingtoolbar.widget;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.android.settingslib.collapsingtoolbar.R;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

/**
 * This widget is wrapping the collapsing toolbar and can be directly used by the
 * {@link AppCompatActivity}.
 */
public class CollapsingCoordinatorLayout extends CoordinatorLayout {
    private static final String TAG = "CollapsingCoordinatorLayout";
    private static final float TOOLBAR_LINE_SPACING_MULTIPLIER = 1.1f;

    private CharSequence mToolbarTitle;
    private boolean mIsMatchParentHeight;
    private CollapsingToolbarLayout mCollapsingToolbarLayout;
    private AppBarLayout mAppBarLayout;

    public CollapsingCoordinatorLayout(@NonNull Context context) {
        this(context, /* attrs= */ null);
    }

    public CollapsingCoordinatorLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, /* defStyleAttr= */ 0);
    }

    public CollapsingCoordinatorLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mIsMatchParentHeight = false;
        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs,
                    R.styleable.CollapsingCoordinatorLayout);
            mToolbarTitle = a.getText(
                    R.styleable.CollapsingCoordinatorLayout_collapsing_toolbar_title);
            mIsMatchParentHeight = a.getBoolean(
                    R.styleable.CollapsingCoordinatorLayout_content_frame_height_match_parent,
                    false);
            a.recycle();
        }
        init();
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (child.getId() == R.id.content_frame && mIsMatchParentHeight) {
            // User want to change the height of content_frame view as match_parent.
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }

        final ViewGroup contentView = findViewById(R.id.content_frame);
        if (contentView != null && isContentFrameChild(child.getId())) {
            contentView.addView(child, index, params);
        } else {
            super.addView(child, index, params);
        }
    }

    private boolean isContentFrameChild(int id) {
        if (id == R.id.app_bar || id == R.id.content_frame) {
            return false;
        }
        return true;
    }

    private void init() {
        inflate(getContext(), R.layout.collapsing_toolbar_content_layout, this);
        mCollapsingToolbarLayout = findViewById(R.id.collapsing_toolbar);
        mAppBarLayout = findViewById(R.id.app_bar);
        if (mCollapsingToolbarLayout != null) {
            mCollapsingToolbarLayout.setLineSpacingMultiplier(TOOLBAR_LINE_SPACING_MULTIPLIER);
            if (!TextUtils.isEmpty(mToolbarTitle)) {
                mCollapsingToolbarLayout.setTitle(mToolbarTitle);
            }
        }
        disableCollapsingToolbarLayoutScrollingBehavior();
    }

    /**
     * Initialize some attributes of {@link ActionBar}.
     *
     * @param activity The host activity using the CollapsingCoordinatorLayout.
     */
    public void initSettingsStyleToolBar(Activity activity) {
        if (activity == null) {
            Log.w(TAG, "initSettingsStyleToolBar: activity is null");
            return;
        }

        if (activity instanceof AppCompatActivity) {
            initSupportToolbar((AppCompatActivity) activity);
            return;
        }

        final Toolbar toolbar = findViewById(R.id.action_bar);
        activity.setActionBar(toolbar);

        // Enable title and home button by default
        final ActionBar actionBar = activity.getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
        }
    }

    /**
     * Initialize some attributes of {@link ActionBar} and assign the title of collapsing toolbar.
     *
     * @param activity The host activity using the CollapsingCoordinatorLayout.
     * @param title    The new title of collapsing toolbar.
     */
    public void initSettingsStyleToolBar(Activity activity, CharSequence title) {
        if (activity == null) {
            Log.w(TAG, "initSettingsStyleToolBar: activity is null");
            return;
        }
        initSettingsStyleToolBar(activity);
        if (!TextUtils.isEmpty(title) && mCollapsingToolbarLayout != null) {
            mToolbarTitle = title;
            mCollapsingToolbarLayout.setTitle(mToolbarTitle);
        }
    }

    /**
     * Returns an instance of collapsing toolbar.
     */
    public CollapsingToolbarLayout getCollapsingToolbarLayout() {
        return mCollapsingToolbarLayout;
    }

    /**
     * Return an instance of app bar.
     */
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

    // This API is for supportActionBar of {@link AppCompatActivity}
    private void initSupportToolbar(AppCompatActivity appCompatActivity) {
        if (mCollapsingToolbarLayout == null) {
            return;
        }

        mCollapsingToolbarLayout.removeAllViews();
        inflate(getContext(), R.layout.support_toolbar, mCollapsingToolbarLayout);
        final androidx.appcompat.widget.Toolbar supportToolbar =
                mCollapsingToolbarLayout.findViewById(R.id.support_action_bar);

        appCompatActivity.setSupportActionBar(supportToolbar);

        // Enable title and home button by default
        final androidx.appcompat.app.ActionBar actionBar = appCompatActivity.getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
        }
    }
}
