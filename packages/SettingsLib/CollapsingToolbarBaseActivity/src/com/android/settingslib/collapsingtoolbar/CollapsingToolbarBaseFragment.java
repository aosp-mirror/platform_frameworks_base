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

import android.app.ActionBar;
import android.content.Context;
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
public abstract class CollapsingToolbarBaseFragment extends Fragment {

    private class DelegateCallback implements CollapsingToolbarDelegate.HostCallback {
        @Nullable
        @Override
        public ActionBar setActionBar(Toolbar toolbar) {
            requireActivity().setActionBar(toolbar);
            return null;
        }

        @Override
        public void setOuterTitle(CharSequence title) {
            // ignore
        }
    }

    private CollapsingToolbarDelegate mToolbardelegate;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mToolbardelegate = new CollapsingToolbarDelegate(new DelegateCallback());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return mToolbardelegate.onCreateView(inflater, container);
    }

    /**
     * Return an instance of CoordinatorLayout.
     */
    @Nullable
    public CoordinatorLayout getCoordinatorLayout() {
        return mToolbardelegate.getCoordinatorLayout();
    }

    /**
     * Return an instance of app bar.
     */
    @Nullable
    public AppBarLayout getAppBarLayout() {
        return mToolbardelegate.getAppBarLayout();
    }

    /**
     * Return the collapsing toolbar layout.
     */
    @Nullable
    public CollapsingToolbarLayout getCollapsingToolbarLayout() {
        return mToolbardelegate.getCollapsingToolbarLayout();
    }

    /**
     * Return the content frame layout.
     */
    @NonNull
    public FrameLayout getContentFrameLayout() {
        return mToolbardelegate.getContentFrameLayout();
    }
}
