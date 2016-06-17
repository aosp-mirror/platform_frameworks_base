/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.Shared.DEBUG;

import android.annotation.Nullable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import com.android.documentsui.dirlist.AnimationView;
import com.android.documentsui.model.RootInfo;

import java.util.function.Consumer;

/**
 * A facade over the portions of the app and drawer toolbars.
 */
public class NavigationViewManager {

    private static final String TAG = "NavigationViewManager";

    final DrawerController mDrawer;
    final DocumentsToolbar mToolbar;
    final State mState;
    final NavigationViewManager.Environment mEnv;
    final Breadcrumb mBreadcrumb;

    public NavigationViewManager(
            DrawerController drawer,
            DocumentsToolbar toolbar,
            State state,
            NavigationViewManager.Environment env,
            Breadcrumb breadcrumb) {

        mToolbar = toolbar;
        mDrawer = drawer;
        mState = state;
        mEnv = env;
        mBreadcrumb = breadcrumb;
        mBreadcrumb.setup(env, state, this::onNavigationItemSelected);

        mToolbar.setNavigationOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onNavigationIconClicked();
                    }
                });
    }

    private void onNavigationIconClicked() {
        if (mDrawer.isPresent()) {
            mDrawer.setOpen(true, DrawerController.OPENED_HAMBURGER);
        }
    }

    void onNavigationItemSelected(int position) {
        boolean changed = false;
        while (mState.stack.size() > position + 1) {
            changed = true;
            mState.popDocument();
        }
        if (changed) {
            mEnv.refreshCurrentRootAndDirectory(AnimationView.ANIM_LEAVE);
        }
    }

    void update() {

        // TODO: Looks to me like this block is never getting hit.
        if (mEnv.isSearchExpanded()) {
            mToolbar.setTitle(null);
            mBreadcrumb.show(false);
            return;
        }

        mDrawer.setTitle(mEnv.getDrawerTitle());

        mToolbar.setNavigationIcon(getActionBarIcon());
        mToolbar.setNavigationContentDescription(R.string.drawer_open);

        if (mState.stack.size() <= 1) {
            mBreadcrumb.show(false);
            String title = mEnv.getCurrentRoot().title;
            if (DEBUG) Log.d(TAG, "New toolbar title is: " + title);
            mToolbar.setTitle(title);
        } else {
            mBreadcrumb.show(true);
            mToolbar.setTitle(null);
            mBreadcrumb.postUpdate();
        }

        if (DEBUG) Log.d(TAG, "Final toolbar title is: " + mToolbar.getTitle());
    }

    // Hamburger if drawer is present, else sad nullness.
    private @Nullable Drawable getActionBarIcon() {
        if (mDrawer.isPresent()) {
            return mToolbar.getContext().getDrawable(R.drawable.ic_hamburger);
        } else {
            return null;
        }
    }

    void revealRootsDrawer(boolean open) {
        mDrawer.setOpen(open);
    }

    interface Breadcrumb {
        void setup(Environment env, State state, Consumer<Integer> listener);
        void show(boolean visibility);
        void postUpdate();
    }

    interface Environment {
        RootInfo getCurrentRoot();
        String getDrawerTitle();
        void refreshCurrentRootAndDirectory(int animation);
        boolean isSearchExpanded();
    }
}
