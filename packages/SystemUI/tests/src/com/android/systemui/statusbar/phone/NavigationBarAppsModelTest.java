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
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tests for the data model for the navigation bar app icons. */
public class NavigationBarAppsModelTest extends AndroidTestCase {
    private PackageManager mMockPackageManager;
    private SharedPreferences mMockPrefs;
    private SharedPreferences.Editor mMockEdit;
    private UserManager mMockUserManager;

    private NavigationBarAppsModel mModel;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Mockito setup boilerplate.
        System.setProperty("dexmaker.dexcache", mContext.getCacheDir().getPath());
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        final Context context = mock(Context.class);
        mMockPackageManager = mock(PackageManager.class);
        mMockPrefs = mock(SharedPreferences.class);
        mMockEdit = mock(SharedPreferences.Editor.class);
        mMockUserManager = mock(UserManager.class);

        when (context.getSharedPreferences(
                "com.android.systemui.navbarapps", Context.MODE_PRIVATE)).thenReturn(mMockPrefs);
        when(context.getSystemService(Context.USER_SERVICE)).thenReturn(mMockUserManager);
        when(context.getPackageManager()).thenReturn(mMockPackageManager);

        setContext(context);

        when(mMockUserManager.getUsers()).thenReturn(new ArrayList<UserInfo>());
        // Assume the version pref is present and equal to the current version.
        when(mMockPrefs.getInt("version", -1)).thenReturn(3);
        when(mMockPrefs.edit()).thenReturn(mMockEdit);

        when(mMockUserManager.getSerialNumberForUser(new UserHandle(2))).thenReturn(22L);

        mModel = new NavigationBarAppsModel(context);
    }

    /** Initializes the model from SharedPreferences for a few app activites. */
    private void initializeModelFromPrefs() {
        // Assume several apps are stored.
        when(mMockPrefs.getInt("22|app_count", -1)).thenReturn(3);
        when(mMockPrefs.getString("22|app_0", null)).thenReturn("package0/class0");
        when(mMockPrefs.getLong("22|app_user_0", -1)).thenReturn(-1L);
        when(mMockPrefs.getString("22|app_1", null)).thenReturn("package1/class1");
        when(mMockPrefs.getLong("22|app_user_1", -1)).thenReturn(45L);
        when(mMockPrefs.getString("22|app_2", null)).thenReturn("package2/class2");
        when(mMockPrefs.getLong("22|app_user_2", -1)).thenReturn(239L);

        mModel.setCurrentUser(2);
    }

    /** Tests initializing the model from SharedPreferences. */
    public void testInitializeFromPrefs() {
        initializeModelFromPrefs();
        assertEquals(2, mModel.getAppCount());
        assertEquals("package1/class1", mModel.getApp(0).getComponentName().flattenToString());
        assertEquals(45L, mModel.getApp(0).getUserSerialNumber());
        assertEquals("package2/class2", mModel.getApp(1).getComponentName().flattenToString());
        assertEquals(239L, mModel.getApp(1).getUserSerialNumber());
    }

    /** Tests initializing the model when the SharedPreferences aren't available. */
    public void testInitializeDefaultApps() {
        // Assume the user's app count pref isn't available.
        when(mMockPrefs.getInt("22|app_count", -1)).thenReturn(-1);

        // Assume some installed activities.
        ActivityInfo ai1 = new ActivityInfo();
        ai1.packageName = "package1";
        ai1.name = "class1";
        ActivityInfo ai2 = new ActivityInfo();
        ai2.packageName = "package2";
        ai2.name = "class2";
        ResolveInfo ri1 = new ResolveInfo();
        ri1.activityInfo = ai1;
        ResolveInfo ri2 = new ResolveInfo();
        ri2.activityInfo = ai2;
        when(mMockPackageManager
                .queryIntentActivitiesAsUser(any(Intent.class), eq(0), eq(2)))
                .thenReturn(Arrays.asList(ri1, ri2));

        // Setting the user should load the installed activities.
        mModel.setCurrentUser(2);
        assertEquals(2, mModel.getAppCount());
        assertEquals("package1/class1", mModel.getApp(0).getComponentName().flattenToString());
        assertEquals(22L, mModel.getApp(0).getUserSerialNumber());
        assertEquals("package2/class2", mModel.getApp(1).getComponentName().flattenToString());
        assertEquals(22L, mModel.getApp(1).getUserSerialNumber());
    }

    /** Tests initializing the model if one of the prefs is missing. */
    public void testInitializeWithMissingPref() {
        // Assume two apps are nominally stored.
        when(mMockPrefs.getInt("22|app_count", -1)).thenReturn(2);
        when(mMockPrefs.getString("22|app_0", null)).thenReturn("package0/class0");
        when(mMockPrefs.getLong("22|app_user_0", -1)).thenReturn(239L);

        // But assume one pref is missing.
        when(mMockPrefs.getString("22|app_1", null)).thenReturn(null);

        // Initializing the model should load from prefs and skip the missing one.
        mModel.setCurrentUser(2);
        assertEquals(1, mModel.getAppCount());
        assertEquals("package0/class0", mModel.getApp(0).getComponentName().flattenToString());
        assertEquals(239L, mModel.getApp(0).getUserSerialNumber());
    }

    /** Tests saving the model to SharedPreferences. */
    public void testSavePrefs() {
        initializeModelFromPrefs();

        mModel.savePrefs();
        verify(mMockEdit).putInt("22|app_count", 2);
        verify(mMockEdit).putString("22|app_0", "package1/class1");
        verify(mMockEdit).putLong("22|app_user_0", 45L);
        verify(mMockEdit).putString("22|app_1", "package2/class2");
        verify(mMockEdit).putLong("22|app_user_1", 239L);
        verify(mMockEdit).apply();
        verifyNoMoreInteractions(mMockEdit);
    }

    /** Tests cleaning all prefs on a version change. */
    public void testVersionChange() {
        // Assume the version pref changed.
        when(mMockPrefs.getInt("version", -1)).thenReturn(1);

        new NavigationBarAppsModel(getContext());
        verify(mMockEdit).clear();
        verify(mMockEdit).putInt("version", 3);
        verify(mMockEdit).apply();
        verifyNoMoreInteractions(mMockEdit);
    }

    /** Tests cleaning prefs for deleted users. */
    public void testCleaningDeletedUsers() {
        // Users on the device.
        final UserInfo user1 = new UserInfo(11, "", 0);
        user1.serialNumber = 1111;
        final UserInfo user2 = new UserInfo(13, "", 0);
        user2.serialNumber = 1313;

        when(mMockUserManager.getUsers()).thenReturn(Arrays.asList(user1, user2));

        when(mMockPrefs.edit()).
                thenReturn(mMockEdit).
                thenReturn(mock(SharedPreferences.Editor.class));

        // Assume the user's app count pref isn't available. This will trigger clearing deleted
        // users' prefs.
        when(mMockPrefs.getInt("22|app_count", -1)).thenReturn(-1);

        final Map allPrefs = new HashMap<String, Object>();
        allPrefs.put("version", null);
        allPrefs.put("some_strange_pref", null);
        allPrefs.put("", null);
        allPrefs.put("|", null);
        allPrefs.put("1313|app_count", null);
        allPrefs.put("1212|app_count", null);
        when(mMockPrefs.getAll()).thenReturn(allPrefs);

        // Setting the user should remove prefs for deleted users.
        mModel.setCurrentUser(2);
        verify(mMockEdit).remove("some_strange_pref");
        verify(mMockEdit).remove("");
        verify(mMockEdit).remove("|");
        verify(mMockEdit).remove("1212|app_count");
        verify(mMockEdit).apply();
        verifyNoMoreInteractions(mMockEdit);
    }
}
