/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.soundpicker;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An adapter used to populate pages inside a ViewPager.
 */
public class ViewPagerAdapter extends FragmentStateAdapter {

    private final List<Fragment> mFragments = new ArrayList<>();
    private final List<String> mTitles = new ArrayList<>();

    public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    /**
     * Adds a fragment and page title to the adapter.
     * @param title the title of the page in the ViewPager.
     * @param fragment the fragment that will be inflated on this page.
     */
    public void addFragment(String title, Fragment fragment) {
        mTitles.add(title);
        mFragments.add(fragment);
    }

    /**
     * Returns the title of the requested page.
     * @param position the position of the page in the Viewpager.
     * @return The title of the requested page.
     */
    public String getTitle(int position) {
        return mTitles.get(position);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return Objects.requireNonNull(mFragments.get(position),
                "Could not find a fragment using position: " + position);
    }

    @Override
    public int getItemCount() {
        return mFragments.size();
    }
}
