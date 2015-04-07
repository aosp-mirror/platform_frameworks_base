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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.test.AndroidTestCase;

import java.util.ArrayList;
import java.util.List;

/** Tests for the data model for the navigation bar app icons. */
public class NavigationBarAppsModelTest extends AndroidTestCase {
    private LauncherApps mMockLauncherApps;
    private SharedPreferences mMockPrefs;

    private NavigationBarAppsModel mModel;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Mockito setup boilerplate.
        System.setProperty("dexmaker.dexcache", mContext.getCacheDir().getPath());
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        mMockLauncherApps = mock(LauncherApps.class);
        mMockPrefs = mock(SharedPreferences.class);
        mModel = new NavigationBarAppsModel(mMockLauncherApps, mMockPrefs);
    }

    /** Initializes the model from SharedPreferences for a few app activites. */
    private void initializeModelFromPrefs() {
        // Assume the version pref is present.
        when(mMockPrefs.getInt("version", -1)).thenReturn(1);

        // Assume several apps are stored.
        when(mMockPrefs.getInt("app_count", -1)).thenReturn(3);
        when(mMockPrefs.getString("app_0", null)).thenReturn("package0/class0");
        when(mMockPrefs.getString("app_1", null)).thenReturn("package1/class1");
        when(mMockPrefs.getString("app_2", null)).thenReturn("package2/class2");

        mModel.initialize();
    }

    /** Tests initializing the model from SharedPreferences. */
    public void testInitializeFromPrefs() {
        initializeModelFromPrefs();
        assertEquals(3, mModel.getAppCount());
        assertEquals("package0/class0", mModel.getApp(0).flattenToString());
        assertEquals("package1/class1", mModel.getApp(1).flattenToString());
        assertEquals("package2/class2", mModel.getApp(2).flattenToString());
    }

    /** Tests initializing the model when the SharedPreferences aren't available. */
    public void testInitializeDefaultApps() {
        // Assume the version pref isn't available.
        when(mMockPrefs.getInt("version", -1)).thenReturn(-1);

        // Assume some installed activities.
        LauncherActivityInfo activity1 = mock(LauncherActivityInfo.class);
        when(activity1.getComponentName()).thenReturn(new ComponentName("package1", "class1"));
        LauncherActivityInfo activity2 = mock(LauncherActivityInfo.class);
        when(activity2.getComponentName()).thenReturn(new ComponentName("package2", "class2"));
        List<LauncherActivityInfo> apps = new ArrayList<LauncherActivityInfo>();
        apps.add(activity1);
        apps.add(activity2);
        when(mMockLauncherApps.getActivityList(anyString(), any(UserHandle.class)))
                .thenReturn(apps);

        // Initializing the model should load the installed activities.
        mModel.initialize();
        assertEquals(2, mModel.getAppCount());
        assertEquals("package1/class1", mModel.getApp(0).flattenToString());
        assertEquals("package2/class2", mModel.getApp(1).flattenToString());
    }

    /** Tests initializing the model if one of the prefs is missing. */
    public void testInitializeWithMissingPref() {
        // Assume the version pref is present.
        when(mMockPrefs.getInt("version", -1)).thenReturn(1);

        // Assume two apps are nominally stored.
        when(mMockPrefs.getInt("app_count", -1)).thenReturn(2);
        when(mMockPrefs.getString("app_0", null)).thenReturn("package0/class0");

        // But assume one pref is missing.
        when(mMockPrefs.getString("app_1", null)).thenReturn(null);

        // Initializing the model should load from prefs and skip the missing one.
        mModel.initialize();
        assertEquals(1, mModel.getAppCount());
        assertEquals("package0/class0", mModel.getApp(0).flattenToString());
    }

    /** Tests saving the model to SharedPreferences. */
    public void testSavePrefs() {
        initializeModelFromPrefs();

        SharedPreferences.Editor mockEdit = mock(SharedPreferences.Editor.class);
        when(mMockPrefs.edit()).thenReturn(mockEdit);

        mModel.savePrefs();
        verify(mockEdit).clear();  // Old prefs were removed.
        verify(mockEdit).putInt("version", 1);
        verify(mockEdit).putInt("app_count", 3);
        verify(mockEdit).putString("app_0", "package0/class0");
        verify(mockEdit).putString("app_1", "package1/class1");
        verify(mockEdit).putString("app_2", "package2/class2");
    }
}
