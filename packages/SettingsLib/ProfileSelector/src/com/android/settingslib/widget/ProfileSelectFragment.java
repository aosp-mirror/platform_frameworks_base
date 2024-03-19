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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.UserProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.os.BuildCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.android.settingslib.widget.profileselector.R;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

/**
 * Base fragment class for profile settings.
 */
public abstract class ProfileSelectFragment extends Fragment {
    private static final String TAG = "ProfileSelectFragment";
    // UserHandle#USER_NULL is a @TestApi so is not accessible.
    private static final int USER_NULL = -10000;
    private static final int DEFAULT_POSITION = 0;

    /**
     * The type of profile tab of {@link ProfileSelectFragment} to show
     * <ul>
     *   <li>0: Personal tab.
     *   <li>1: Work profile tab.
     * </ul>
     *
     * <p> Please note that this is supported for legacy reasons. Please use
     * {@link #EXTRA_SHOW_FRAGMENT_USER_ID} instead.
     */
    public static final String EXTRA_SHOW_FRAGMENT_TAB = ":settings:show_fragment_tab";

    /**
     * An {@link ArrayList} of users to show. The supported users are: System user, the managed
     * profile user, and the private profile user. A client should pass all the user ids that need
     * to be shown in this list. Note that if this list is not provided then, for legacy reasons
     * see {@link #EXTRA_SHOW_FRAGMENT_TAB}, an attempt will be made to show two tabs: one for the
     * System user and one for the managed profile user.
     *
     * <p>Please note that this MUST be used in conjunction with
     * {@link #EXTRA_SHOW_FRAGMENT_USER_ID}
     */
    public static final String EXTRA_LIST_OF_USER_IDS = ":settings:list_user_ids";

    /**
     * The user id of the user to be show in {@link ProfileSelectFragment}. Only the below user
     * types are supported:
     * <ul>
     *   <li> System user.
     *   <li> Managed profile user.
     *   <li> Private profile user.
     * </ul>
     *
     * <p>Please note that this MUST be used in conjunction with {@link #EXTRA_LIST_OF_USER_IDS}.
     */
    public static final String EXTRA_SHOW_FRAGMENT_USER_ID = ":settings:show_fragment_user_id";

    /**
     * Used in fragment argument with Extra key EXTRA_SHOW_FRAGMENT_TAB
     */
    public static final int PERSONAL_TAB = 0;

    /**
     * Used in fragment argument with Extra key EXTRA_SHOW_FRAGMENT_TAB for the managed profile
     */
    public static final int WORK_TAB = 1;

    /**
     * Please note that private profile is available from API LEVEL
     * {@link Build.VERSION_CODES.VANILLA_ICE_CREAM} only, therefore PRIVATE_TAB MUST be
     * passed in {@link #EXTRA_SHOW_FRAGMENT_TAB} and {@link #EXTRA_LIST_OF_PROFILE_TABS} for
     * {@link Build.VERSION_CODES.VANILLA_ICE_CREAM} or higher API Levels only.
     */
    private static final int PRIVATE_TAB = 2;

    private ViewGroup mContentView;

    private ViewPager2 mViewPager;
    private final ArrayMap<UserHandle, Integer> mProfileTabsByUsers = new ArrayMap<>();
    private boolean mUsingUserIds = false;

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
        initProfileTabsToShow();

        final View tabContainer = mContentView.findViewById(R.id.tab_container);
        mViewPager = tabContainer.findViewById(R.id.view_pager);
        mViewPager.setAdapter(new ProfileViewPagerAdapter(this));
        final TabLayout tabs = tabContainer.findViewById(R.id.tabs);
        new TabLayoutMediator(tabs, mViewPager,
                (tab, position) -> tab.setText(getPageTitle(position))
        ).attach();

        tabContainer.setVisibility(View.VISIBLE);
        final TabLayout.Tab tab = tabs.getTabAt(getSelectedTabPosition(activity, getArguments()));
        tab.select();

        return mContentView;
    }

    /**
     * Create Personal or Work or Private profile fragment. See {@link #EXTRA_SHOW_FRAGMENT_USER_ID}
     */
    public abstract Fragment createFragment(int position);

    /**
     * Returns a resource ID of the title
     * Override this if the title needs to be updated dynamically.
     */
    public int getTitleResId() {
        return 0;
    }

    int getSelectedTabPosition(Activity activity, Bundle bundle) {
        if (bundle != null) {
            final int extraUserId = bundle.getInt(EXTRA_SHOW_FRAGMENT_USER_ID, USER_NULL);
            if (extraUserId != USER_NULL) {
                return mProfileTabsByUsers.indexOfKey(UserHandle.of(extraUserId));
            }
            final int extraTab = bundle.getInt(EXTRA_SHOW_FRAGMENT_TAB, -1);
            if (extraTab != -1) {
                return extraTab;
            }
        }
        return DEFAULT_POSITION;
    }

    int getTabCount() {
        return mUsingUserIds ? mProfileTabsByUsers.size() : 2;
    }

    void initProfileTabsToShow() {
        Bundle bundle = getArguments();
        if (bundle != null) {
            ArrayList<Integer> userIdsToShow =
                    bundle.getIntegerArrayList(EXTRA_LIST_OF_USER_IDS);
            if (userIdsToShow != null && !userIdsToShow.isEmpty()) {
                mUsingUserIds = true;
                UserManager userManager = getContext().getSystemService(UserManager.class);
                List<UserHandle> userHandles = userManager.getUserProfiles();
                for (UserHandle userHandle : userHandles) {
                    if (!userIdsToShow.contains(userHandle.getIdentifier())) {
                        continue;
                    }
                    if (userHandle.isSystem()) {
                        mProfileTabsByUsers.put(userHandle, PERSONAL_TAB);
                    } else if (userManager.isManagedProfile(userHandle.getIdentifier())) {
                        mProfileTabsByUsers.put(userHandle, WORK_TAB);
                    } else if (shouldShowPrivateProfileIfItsOne(userHandle)) {
                        mProfileTabsByUsers.put(userHandle, PRIVATE_TAB);
                    }
                }
            }
        }
    }

    private int getProfileTabForPosition(int position) {
        return mUsingUserIds ? mProfileTabsByUsers.valueAt(position) : position;
    }

    int getUserIdForPosition(int position) {
        return mUsingUserIds ? mProfileTabsByUsers.keyAt(position).getIdentifier() : position;
    }

    private CharSequence getPageTitle(int position) {
        int tab = getProfileTabForPosition(position);
        if (tab == WORK_TAB) {
            return getContext().getString(R.string.settingslib_category_work);
        } else if (tab == PRIVATE_TAB) {
            return getContext().getString(R.string.settingslib_category_private);
        }

        return getString(R.string.settingslib_category_personal);
    }

    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private boolean shouldShowUserInQuietMode(UserHandle userHandle, UserManager userManager) {
        UserProperties userProperties = userManager.getUserProperties(userHandle);
        return !userManager.isQuietModeEnabled(userHandle)
                || userProperties.getShowInQuietMode() != UserProperties.SHOW_IN_QUIET_MODE_HIDDEN;
    }

    // It's sufficient to have this method marked with the appropriate API level because we expect
    // to be here only for this API level - when then private profile was introduced.
    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private boolean shouldShowPrivateProfileIfItsOne(UserHandle userHandle) {
        if (!BuildCompat.isAtLeastV() || !android.os.Flags.allowPrivateProfile()) {
            return false;
        }
        try {
            Context userContext = getContext().createContextAsUser(userHandle, /* flags= */ 0);
            UserManager userManager = userContext.getSystemService(UserManager.class);
            return userManager.isPrivateProfile()
                    && shouldShowUserInQuietMode(userHandle, userManager);
        } catch (IllegalStateException exception) {
            Log.i(TAG, "Ignoring this user as the calling package not available in this user.");
        }
        return false;
    }
}
