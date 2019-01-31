/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.widget;

import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import android.app.ActionBar;
import android.app.Activity;
import android.view.View;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.recyclerview.widget.RecyclerView;

/**
 * UI controller that adds a shadow appear/disappear animation to action bar scroll.
 */
public class ActionBarShadowController implements LifecycleObserver {

    @VisibleForTesting
    static final float ELEVATION_HIGH = 8;
    @VisibleForTesting
    static final float ELEVATION_LOW = 0;

    @VisibleForTesting
    ScrollChangeWatcher mScrollChangeWatcher;
    private RecyclerView mRecyclerView;
    private boolean mIsScrollWatcherAttached;

    /**
     * Wire up the animation to to an {@link Activity}. Shadow will be applied to activity's
     * action bar.
     */
    public static ActionBarShadowController attachToRecyclerView(
            Activity activity, Lifecycle lifecycle, RecyclerView recyclerView) {
        return new ActionBarShadowController(activity, lifecycle, recyclerView);
    }

    /**
     * Wire up the animation to to a {@link View}. Shadow will be applied to the view.
     */
    public static ActionBarShadowController attachToRecyclerView(
            View anchorView, Lifecycle lifecycle, RecyclerView recyclerView) {
        return new ActionBarShadowController(anchorView, lifecycle, recyclerView);
    }

    private ActionBarShadowController(Activity activity, Lifecycle lifecycle,
            RecyclerView recyclerView) {
        mScrollChangeWatcher =
                new ActionBarShadowController.ScrollChangeWatcher(activity);
        mRecyclerView = recyclerView;
        attachScrollWatcher();
        lifecycle.addObserver(this);
    }

    private ActionBarShadowController(View anchorView, Lifecycle lifecycle,
            RecyclerView recyclerView) {
        mScrollChangeWatcher =
                new ActionBarShadowController.ScrollChangeWatcher(anchorView);
        mRecyclerView = recyclerView;
        attachScrollWatcher();
        lifecycle.addObserver(this);
    }

    @OnLifecycleEvent(ON_START)
    private void attachScrollWatcher() {
        if (!mIsScrollWatcherAttached) {
            mIsScrollWatcherAttached = true;
            mRecyclerView.addOnScrollListener(mScrollChangeWatcher);
            mScrollChangeWatcher.updateDropShadow(mRecyclerView);
        }
    }

    @OnLifecycleEvent(ON_STOP)
    private void detachScrollWatcher() {
        mRecyclerView.removeOnScrollListener(mScrollChangeWatcher);
        mIsScrollWatcherAttached = false;
    }

    /**
     * Update the drop shadow as the scrollable entity is scrolled.
     */
    final class ScrollChangeWatcher extends RecyclerView.OnScrollListener {

        private final Activity mActivity;
        private final View mAnchorView;

        ScrollChangeWatcher(Activity activity) {
            mActivity = activity;
            mAnchorView = null;
        }

        ScrollChangeWatcher(View anchorView) {
            mAnchorView = anchorView;
            mActivity = null;
        }

        // RecyclerView scrolled.
        @Override
        public void onScrolled(RecyclerView view, int dx, int dy) {
            updateDropShadow(view);
        }

        public void updateDropShadow(View view) {
            final boolean shouldShowShadow = view.canScrollVertically(-1);
            if (mAnchorView != null) {
                mAnchorView.setElevation(shouldShowShadow ? ELEVATION_HIGH : ELEVATION_LOW);
            } else if (mActivity != null) { // activity can become null when running monkey
                final ActionBar actionBar = mActivity.getActionBar();
                if (actionBar != null) {
                    actionBar.setElevation(shouldShowShadow ? ELEVATION_HIGH : ELEVATION_LOW);
                }
            }
        }
    }
}
