/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model and controller for app icons appearing in the navigation bar. The data is stored on
 * disk in SharedPreferences. Each icon has a separate pref entry consisting of a flattened
 * ComponentName.
 */
class NavigationBarAppsModel {
    private final static String TAG = "NavigationBarAppsModel";

    // Default number of apps to load initially.
    private final static int NUM_INITIAL_APPS = 4;

    // Preferences file name.
    private final static String SHARED_PREFERENCES_NAME = "com.android.systemui.navbarapps";

    // Preference name for the version of the other preferences.
    private final static String VERSION_PREF = "version";

    // Current version number for preferences.
    private final static int CURRENT_VERSION = 1;

    // Preference name for the number of app icons.
    private final static String APP_COUNT_PREF = "app_count";

    // Preference name prefix for each app's info. The actual pref has an integer appended to it.
    private final static String APP_PREF_PREFIX = "app_";

    private final LauncherApps mLauncherApps;
    private final SharedPreferences mPrefs;

    // Apps are represented as an ordered list of component names.
    private final List<ComponentName> mApps = new ArrayList<ComponentName>();

    public NavigationBarAppsModel(Context context) {
        mLauncherApps = (LauncherApps) context.getSystemService("launcherapps");
        mPrefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @VisibleForTesting
    NavigationBarAppsModel(LauncherApps launcherApps, SharedPreferences prefs) {
        mLauncherApps = launcherApps;
        mPrefs = prefs;
    }

    /**
     * Initializes the model with a list of apps, either by loading it off disk or by supplying
     * a default list.
     */
    public void initialize() {
        if (mApps.size() > 0) {
            Slog.e(TAG, "Model already initialized");
            return;
        }

        // Check for an existing list of apps.
        int version = mPrefs.getInt(VERSION_PREF, -1);
        if (version == CURRENT_VERSION) {
            loadAppsFromPrefs();
        } else {
            addDefaultApps();
        }
    }

    /** Returns the number of apps. */
    public int getAppCount() {
        return mApps.size();
    }

    /** Returns the app at the given index. */
    public ComponentName getApp(int index) {
        return mApps.get(index);
    }

    /** Adds the app before the given index. */
    public void addApp(int index, ComponentName name) {
        mApps.add(index, name);
    }

    /** Remove the app at the given index. */
    public ComponentName removeApp(int index) {
        return mApps.remove(index);
    }

    /** Saves the current model to disk. */
    public void savePrefs() {
        SharedPreferences.Editor edit = mPrefs.edit();
        // The user might have removed icons, so clear all the old prefs.
        edit.clear();
        edit.putInt(VERSION_PREF, CURRENT_VERSION);
        int appCount = mApps.size();
        edit.putInt(APP_COUNT_PREF, appCount);
        for (int i = 0; i < appCount; i++) {
            String componentNameString = mApps.get(i).flattenToString();
            edit.putString(prefNameForApp(i), componentNameString);
        }
        // Start an asynchronous disk write.
        edit.apply();
    }

    /** Loads the list of apps from SharedPreferences. */
    private void loadAppsFromPrefs() {
        int appCount = mPrefs.getInt(APP_COUNT_PREF, -1);
        for (int i = 0; i < appCount; i++) {
            String prefValue = mPrefs.getString(prefNameForApp(i), null);
            if (prefValue == null) {
                Slog.w(TAG, "Couldn't find pref " + prefNameForApp(i));
                // Couldn't find the saved state. Just skip this item.
                continue;
            }
            ComponentName componentName = ComponentName.unflattenFromString(prefValue);
            mApps.add(componentName);
        }
    }

    /** Adds the first few apps from the owner profile. Used for demo purposes. */
    private void addDefaultApps() {
        // Get a list of all app activities.
        List<LauncherActivityInfo> apps =
                mLauncherApps.getActivityList(null /* packageName */, UserHandle.OWNER);
        int appCount = apps.size();
        for (int i = 0; i < NUM_INITIAL_APPS && i < appCount; i++) {
            LauncherActivityInfo activityInfo = apps.get(i);
            mApps.add(activityInfo.getComponentName());
        }
    }

    /** Returns the pref name for the app at a given index. */
    private static String prefNameForApp(int index) {
        return APP_PREF_PREFIX + Integer.toString(index);
    }
}
