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

package com.android.settingslib.widget;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.android.settingslib.widget.profileselector.R;

/**
 * Base fragment class for profile settings.
 */
public abstract class ProfileSelectFragment extends Fragment {

    /**
     * Personal or Work profile tab of {@link ProfileSelectFragment}
     * <p>0: Personal tab.
     * <p>1: Work profile tab.
     */
    public static final String EXTRA_SHOW_FRAGMENT_TAB =
            ":settings:show_fragment_tab";

    /**
     * Used in fragment argument with Extra key EXTRA_SHOW_FRAGMENT_TAB
     */
    public static final int PERSONAL_TAB = 0;

    /**
     * Used in fragment argument with Extra key EXTRA_SHOW_FRAGMENT_TAB
     */
    public static final int WORK_TAB = 1;

    private ViewGroup mContentView;

    private ViewPager2 mViewPager;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        // Defines the xml file for the fragment
        mContentView = (ViewGroup) inflater.inflate(R.layout.tab_fragment, container, false);

        final Activity activity = getActivity();
        final int titleResId = getTitleResId();
        if (titleResId > 0) {
            activity.setTitle(titleResId);
        }
        final int selectedTab = getTabId(activity, getArguments());

        final View tabContainer = mContentView.findViewById(R.id.tab_container);
        mViewPager = tabContainer.findViewById(R.id.view_pager);
        mViewPager.setAdapter(new ProfileViewPagerAdapter(this));
        final TabLayout tabs = tabContainer.findViewById(R.id.tabs);
        new TabLayoutMediator(tabs, mViewPager,
                (tab, position) -> tab.setText(getPageTitle(position))
        ).attach();

        tabContainer.setVisibility(View.VISIBLE);
        final TabLayout.Tab tab = tabs.getTabAt(selectedTab);
        tab.select();

        return mContentView;
    }

    /**
     * create Personal or Work profile fragment
     * <p>0: Personal profile.
     * <p>1: Work profile.
     */
    public abstract Fragment createFragment(int position);

    /**
     * Returns a resource ID of the title
     * Override this if the title needs to be updated dynamically.
     */
    public int getTitleResId() {
        return 0;
    }

    int getTabId(Activity activity, Bundle bundle) {
        if (bundle != null) {
            final int extraTab = bundle.getInt(EXTRA_SHOW_FRAGMENT_TAB, -1);
            if (extraTab != -1) {
                return extraTab;
            }
        }
        return PERSONAL_TAB;
    }

    private CharSequence getPageTitle(int position) {
        if (position == WORK_TAB) {
            return getContext().getString(R.string.settingslib_category_work);
        }

        return getString(R.string.settingslib_category_personal);
    }
}
