/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.userswitcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;

import com.android.systemui.R;
import com.android.systemui.car.window.OverlayViewController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller for {@link R.layout#car_fullscreen_user_switcher}.
 */
@Singleton
public class FullScreenUserSwitcherViewController extends OverlayViewController {
    private final Context mContext;
    private final Resources mResources;
    private final int mShortAnimationDuration;
    private UserGridRecyclerView mUserGridView;
    private UserGridRecyclerView.UserSelectionListener mUserSelectionListener;

    @Inject
    public FullScreenUserSwitcherViewController(
            Context context,
            @Main Resources resources,
            OverlayViewGlobalStateController overlayViewGlobalStateController) {
        super(R.id.fullscreen_user_switcher_stub, overlayViewGlobalStateController);
        mContext = context;
        mResources = resources;
        mShortAnimationDuration = mResources.getInteger(android.R.integer.config_shortAnimTime);
    }

    @Override
    protected void onFinishInflate() {
        // Initialize user grid.
        mUserGridView = getLayout().findViewById(R.id.user_grid);
        GridLayoutManager layoutManager = new GridLayoutManager(mContext,
                mResources.getInteger(R.integer.user_fullscreen_switcher_num_col));
        mUserGridView.setLayoutManager(layoutManager);
        mUserGridView.buildAdapter();
        mUserGridView.setUserSelectionListener(mUserSelectionListener);
    }

    @Override
    protected void showInternal() {
        getLayout().setVisibility(View.VISIBLE);
    }

    @Override
    protected void hideInternal() {
        // Switching is about to happen, since it takes time, fade out the switcher gradually.
        fadeOut();
    }

    private void fadeOut() {
        mUserGridView.animate()
                .alpha(0.0f)
                .setDuration(mShortAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        getLayout().setVisibility(View.GONE);
                        mUserGridView.setAlpha(1.0f);
                    }
                });

    }

    /**
     * Invalidate underlying view.
     */
    void invalidate() {
        if (getLayout() == null) {
            // layout hasn't been inflated.
            return;
        }

        getLayout().invalidate();
    }

    /**
     * Set {@link UserGridRecyclerView.UserSelectionListener}.
     */
    void setUserGridSelectionListener(
            UserGridRecyclerView.UserSelectionListener userGridSelectionListener) {
        mUserSelectionListener = userGridSelectionListener;
    }

    /**
     * Returns {@code true} when layout is visible.
     */
    boolean isVisible() {
        if (getLayout() == null) {
            // layout hasn't been inflated.
            return false;
        }

        return getLayout().getVisibility() == View.VISIBLE;
    }
}
