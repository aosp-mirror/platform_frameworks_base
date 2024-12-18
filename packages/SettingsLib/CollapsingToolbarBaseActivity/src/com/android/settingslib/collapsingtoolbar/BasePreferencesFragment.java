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

import android.os.Build;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.AppBarLayout;

/**
 * A base fragment that supports multi-fragments in one activity. The activity likes to switch the
 * different fragments which extend this base fragment and must use the following code to add the
 * fragment to stack.
 *
 * protected void onCreate(Bundle savedState) {
 *    // omitted…
 *    getFragmentManager()
 *            .beginTransaction()
 *            .add(R.id.content_frame, new Your_Fragment())
 *            // Add root page to back-history
 *            .addToBackStack( null)
 *            .commit();
 *    // omitted
 * }
 */
public abstract class BasePreferencesFragment extends PreferenceFragmentCompat {

    /**
     * Gets the title which the fragment likes to show on app bar. The child class must implement
     * this
     * function.
     *
     * @return The title of the fragment will show on app bar.
     */
    public abstract CharSequence getTitle();

    @Override
    public void onResume() {
        super.onResume();

        FragmentActivity activity = getActivity();
        if (activity != null) {
            activity.setTitle(getTitle());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AppBarLayout appBarLayout = (AppBarLayout) activity.findViewById(R.id.app_bar);

                if (appBarLayout != null) {
                    appBarLayout.setExpanded(/* expanded= */ true);
                }
            }
        }
    }
}
