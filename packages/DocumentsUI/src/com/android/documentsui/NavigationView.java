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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.android.documentsui.Shared.DEBUG;

import android.annotation.Nullable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.documentsui.dirlist.AnimationView;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;

/**
 * A facade over the portions of the app and drawer toolbars.
 */
class NavigationView {

    private static final String TAG = "NavigationView";

    private final DrawerController mDrawer;
    private final DocumentsToolbar mToolbar;
    private final Spinner mBreadcrumb;
    private final State mState;
    private final NavigationView.Environment mEnv;
    private final BreadcrumbAdapter mBreadcrumbAdapter;

    private boolean mIgnoreNextNavigation;

    public NavigationView(
            DrawerController drawer,
            DocumentsToolbar toolbar,
            Spinner breadcrumb,
            State state,
            NavigationView.Environment env) {

        mToolbar = toolbar;
        mBreadcrumb = breadcrumb;
        mDrawer = drawer;
        mState = state;
        mEnv = env;

        mBreadcrumbAdapter = new BreadcrumbAdapter(mState, mEnv);
        mToolbar.setNavigationOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onNavigationIconClicked();
                    }

                });

        mBreadcrumb.setOnItemSelectedListener(
                new OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        onBreadcrumbItemSelected(position);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });

    }

    private void onNavigationIconClicked() {
        if (mDrawer.isPresent()) {
            mDrawer.setOpen(true, DrawerController.OPENED_HAMBURGER);
        }
    }

    private void onBreadcrumbItemSelected(int position) {
        if (mIgnoreNextNavigation) {
            mIgnoreNextNavigation = false;
            return;
        }

        while (mState.stack.size() > position + 1) {
            mState.popDocument();
        }
        mEnv.refreshCurrentRootAndDirectory(AnimationView.ANIM_LEAVE);
    }

    void update() {

        // TODO: Looks to me like this block is never getting hit.
        if (mEnv.isSearchExpanded()) {
            mToolbar.setTitle(null);
            mBreadcrumb.setVisibility(View.GONE);
            mBreadcrumb.setAdapter(null);
            return;
        }

        mDrawer.setTitle(mEnv.getDrawerTitle());

        mToolbar.setNavigationIcon(getActionBarIcon());
        mToolbar.setNavigationContentDescription(R.string.drawer_open);

        if (mState.stack.size() <= 1) {
            showBreadcrumb(false);
            String title = mEnv.getCurrentRoot().title;
            if (DEBUG) Log.d(TAG, "New toolbar title is: " + title);
            mToolbar.setTitle(title);
        } else {
            showBreadcrumb(true);
            mToolbar.setTitle(null);
            mIgnoreNextNavigation = true;
            mBreadcrumb.setSelection(mBreadcrumbAdapter.getCount() - 1, false);
        }

        if (DEBUG) Log.d(TAG, "Final toolbar title is: " + mToolbar.getTitle());
    }

    private void showBreadcrumb(boolean visibility) {
        if (visibility) {
            mBreadcrumb.setVisibility(VISIBLE);
            mBreadcrumb.setAdapter(mBreadcrumbAdapter);
        } else {
            mBreadcrumb.setVisibility(GONE);
            mBreadcrumb.setAdapter(null);
        }
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

    /**
     * Class providing toolbar with runtime access to useful activity data.
     */
    static final class BreadcrumbAdapter extends BaseAdapter {

        private Environment mEnv;
        private State mState;

        public BreadcrumbAdapter(State state, Environment env) {
            mState = state;
            mEnv = env;
        }

        @Override
        public int getCount() {
            return mState.stack.size();
        }

        @Override
        public DocumentInfo getItem(int position) {
            return mState.stack.get(mState.stack.size() - position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir_title, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = mEnv.getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = mEnv.getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }

            return convertView;
        }
    }

    interface Environment {
        RootInfo getCurrentRoot();
        String getDrawerTitle();
        void refreshCurrentRootAndDirectory(int animation);
        boolean isSearchExpanded();
    }
}
