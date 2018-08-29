/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.testutils;

import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import org.robolectric.Robolectric;

/**
 * Utilities for creating Fragments for testing.
 * <p>
 * TODO(b/111195449) - Duplicated from org.robolectric.shadows.support.v4.SupportFragmentTestUtil
 */
@Deprecated
public class FragmentTestUtils {

    public static void startFragment(Fragment fragment) {
        buildFragmentManager(FragmentUtilActivity.class)
                .beginTransaction().add(fragment, null).commit();
    }

    public static void startFragment(Fragment fragment,
            Class<? extends FragmentActivity> activityClass) {
        buildFragmentManager(activityClass)
                .beginTransaction().add(fragment, null).commit();
    }

    public static void startVisibleFragment(Fragment fragment) {
        buildFragmentManager(FragmentUtilActivity.class)
                .beginTransaction().add(1, fragment, null).commit();
    }

    public static void startVisibleFragment(Fragment fragment,
            Class<? extends FragmentActivity> activityClass, int containerViewId) {
        buildFragmentManager(activityClass)
                .beginTransaction().add(containerViewId, fragment, null).commit();
    }

    private static FragmentManager buildFragmentManager(
            Class<? extends FragmentActivity> activityClass) {
        FragmentActivity activity = Robolectric.setupActivity(activityClass);
        return activity.getSupportFragmentManager();
    }

    private static class FragmentUtilActivity extends FragmentActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout view = new LinearLayout(this);
            view.setId(1);

            setContentView(view);
        }
    }
}
