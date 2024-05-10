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

import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import com.android.settingslib.widget.profileselector.R;

/**
 * ViewPager Adapter to handle between TabLayout and ViewPager2
 */
public class ProfileViewPagerAdapter extends FragmentStateAdapter {

    private final ProfileSelectFragment mParentFragments;

    ProfileViewPagerAdapter(ProfileSelectFragment fragment) {
        super(fragment);
        mParentFragments = fragment;
    }

    @Override
    public Fragment createFragment(int position) {
        return mParentFragments.createFragment(position);
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
