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
import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.text.LineBreakConfig;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

/**
 * A delegate that allows to use the collapsing toolbar layout in hosts that doesn't want/need to
 * extend from {@link CollapsingToolbarBaseActivity} or from {@link CollapsingToolbarBaseFragment}.
 */
public class CollapsingToolbarDelegate {
    private static final String TAG = "CTBdelegate";
    /** Interface to be implemented by the host of the Collapsing Toolbar. */
    public interface HostCallback {
        /**
         * Called when a Toolbar should be set on the host.
         *
         * <p>If the host wants action bar to be modified, it should return it.
         */
        @Nullable
        ActionBar setActionBar(Toolbar toolbar);

        /** Sets support tool bar and return support action bar, this is for AppCompatActivity. */
        @Nullable
        default androidx.appcompat.app.ActionBar setActionBar(
                androidx.appcompat.widget.Toolbar toolbar) {
            return null;
        }

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

    private boolean mUseCollapsingToolbar;

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback,
            boolean useCollapsingToolbar) {
        mHostCallback = hostCallback;
        mUseCollapsingToolbar = useCollapsingToolbar;
    }

    /** Method to call that creates the root view of the collapsing toolbar. */
    @SuppressWarnings("RestrictTo")
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return onCreateView(inflater, container, null);
    }

    /** Method to call that creates the root view of the collapsing toolbar. */
    @SuppressWarnings("RestrictTo")
    View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            Activity activity) {
        int layoutId;
        boolean useCollapsingToolbar =
                mUseCollapsingToolbar || Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
        if (useCollapsingToolbar) {
            layoutId = R.layout.collapsing_toolbar_base_layout;
        } else {
            layoutId = R.layout.non_collapsing_toolbar_base_layout;
        }
        final View view = inflater.inflate(layoutId, container, false);
        if (view instanceof CoordinatorLayout) {
            mCoordinatorLayout = (CoordinatorLayout) view;
        }
        mCollapsingToolbarLayout = view.findViewById(R.id.collapsing_toolbar);
        mAppBarLayout = view.findViewById(R.id.app_bar);

        if (!useCollapsingToolbar) {
            // In the non-collapsing toolbar layout, we need to set the background of the app bar to
            // the same as the activity background so that it covers the items extending above the
            // bounds of the list for edge-to-edge.
            TypedArray ta = container.getContext().obtainStyledAttributes(new int[] {
                    android.R.attr.windowBackground});
            Drawable background = ta.getDrawable(0);
            ta.recycle();
            mAppBarLayout.setBackground(background);
        }

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
        autoSetCollapsingToolbarLayoutScrolling();
        mContentFrameLayout = view.findViewById(R.id.content_frame);
        if (activity instanceof AppCompatActivity) {
            Log.d(TAG, "onCreateView: from AppCompatActivity and sub-class.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                initSupportActionBar(inflater);
            } else {
                initRSupportActionBar(view);
            }
        } else {
            Log.d(TAG, "onCreateView: from NonAppCompatActivity.");
            mToolbar = view.findViewById(R.id.action_bar);
            final ActionBar actionBar = mHostCallback.setActionBar(mToolbar);
            // Enable title and home button by default
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setHomeButtonEnabled(true);
                actionBar.setDisplayShowTitleEnabled(true);
            }
        }
        return view;
    }

    private void initSupportActionBar(@NonNull LayoutInflater inflater) {
        if (mCollapsingToolbarLayout == null) {
            return;
        }
        mCollapsingToolbarLayout.removeAllViews();
        inflater.inflate(R.layout.support_toolbar, mCollapsingToolbarLayout);
        final androidx.appcompat.widget.Toolbar supportToolbar =
                mCollapsingToolbarLayout.findViewById(R.id.support_action_bar);
        final androidx.appcompat.app.ActionBar actionBar =
                mHostCallback.setActionBar(supportToolbar);
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
        }
    }

    private void initRSupportActionBar(View view) {
        view.findViewById(R.id.action_bar).setVisibility(View.GONE);
        final androidx.appcompat.widget.Toolbar supportToolbar =
                view.findViewById(R.id.support_action_bar);
        supportToolbar.setVisibility(View.VISIBLE);
        final androidx.appcompat.app.ActionBar actionBar =
                mHostCallback.setActionBar(supportToolbar);
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
        }
    }

    /** Return an instance of CoordinatorLayout. */
    @Nullable
    public CoordinatorLayout getCoordinatorLayout() {
        return mCoordinatorLayout;
    }

    /** Sets the title on the collapsing layout and delegates to host. */
    public void setTitle(CharSequence title) {
        if (mCollapsingToolbarLayout != null) {
            mCollapsingToolbarLayout.setTitle(title);
        }
        mHostCallback.setOuterTitle(title);
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

    private void autoSetCollapsingToolbarLayoutScrolling() {
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
                        // Header can be scrolling while device in landscape mode and SDK > 33
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
                            return false;
                        } else {
                            return appBarLayout.getResources().getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE;
                        }
                    }
                });
        params.setBehavior(behavior);
    }
}
