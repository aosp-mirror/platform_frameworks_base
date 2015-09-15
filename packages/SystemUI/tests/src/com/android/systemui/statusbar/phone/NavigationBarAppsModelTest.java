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

import org.mockito.InOrder;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.RemoteException;
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
    private IPackageManager mMockIPackageManager;
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
        mMockIPackageManager = mock(IPackageManager.class);
        mMockPrefs = mock(SharedPreferences.class);
        mMockEdit = mock(SharedPreferences.Editor.class);
        mMockUserManager = mock(UserManager.class);

        when(context.getSharedPreferences(
                "com.android.systemui.navbarapps", Context.MODE_PRIVATE)).thenReturn(mMockPrefs);
        when(context.getSystemService(Context.USER_SERVICE)).thenReturn(mMockUserManager);
        when(context.getPackageManager()).thenReturn(mMockPackageManager);

        setContext(context);

        when(mMockUserManager.getUsers()).thenReturn(new ArrayList<UserInfo>());
        // Assume the version pref is present and equal to the current version.
        when(mMockPrefs.getInt("version", -1)).thenReturn(3);
        when(mMockPrefs.edit()).thenReturn(mMockEdit);

        when(mMockUserManager.getSerialNumberForUser(new UserHandle(2))).thenReturn(222L);
        when(mMockUserManager.getSerialNumberForUser(new UserHandle(4))).thenReturn(444L);
        when(mMockUserManager.getSerialNumberForUser(new UserHandle(5))).thenReturn(555L);
        when(mMockUserManager.getUserForSerialNumber(222L)).thenReturn(new UserHandle(2));
        when(mMockUserManager.getUserForSerialNumber(444L)).thenReturn(new UserHandle(4));
        when(mMockUserManager.getUserForSerialNumber(555L)).thenReturn(new UserHandle(5));

        UserInfo ui2 = new UserInfo();
        ui2.profileGroupId = 999;
        UserInfo ui4 = new UserInfo();
        ui4.profileGroupId = 999;
        UserInfo ui5 = new UserInfo();
        ui5.profileGroupId = 999;
        when(mMockUserManager.getUserInfo(2)).thenReturn(ui2);
        when(mMockUserManager.getUserInfo(4)).thenReturn(ui4);
        when(mMockUserManager.getUserInfo(5)).thenReturn(ui5);

        mModel = new NavigationBarAppsModel(context) {
            @Override
            protected IPackageManager getPackageManager() {
                return mMockIPackageManager;
            }
        };
    }

    /** Tests buildAppLaunchIntent(). */
    public void testBuildAppLaunchIntent() {
        ActivityInfo mockNonExportedActivityInfo = new ActivityInfo();
        mockNonExportedActivityInfo.exported = false;
        ActivityInfo mockExportedActivityInfo = new ActivityInfo();
        mockExportedActivityInfo.exported = true;
        try {
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package1", "class1"), 0, 4)).
                    thenReturn(mockNonExportedActivityInfo);
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package2", "class2"), 0, 5)).
                    thenThrow(new RemoteException());
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package3", "class3"), 0, 6)).
                    thenReturn(mockExportedActivityInfo);
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package4", "class4"), 0, 7)).
                    thenReturn(mockExportedActivityInfo);
        } catch (RemoteException e) {
            fail("RemoteException can't happen in the test, but it happened.");
        }

        // Assume some installed activities.
        ActivityInfo ai0 = new ActivityInfo();
        ai0.packageName = "package0";
        ai0.name = "class0";
        ActivityInfo ai1 = new ActivityInfo();
        ai1.packageName = "package4";
        ai1.name = "class4";
        ResolveInfo ri0 = new ResolveInfo();
        ri0.activityInfo = ai0;
        ResolveInfo ri1 = new ResolveInfo();
        ri1.activityInfo = ai1;
        when(mMockPackageManager
                .queryIntentActivitiesAsUser(any(Intent.class), eq(0), any(int.class)))
                .thenReturn(Arrays.asList(ri0, ri1));

        mModel.setCurrentUser(3);
        // Unlauncheable (for various reasons) apps.
        assertEquals(null, mModel.buildAppLaunchIntent(
                new AppInfo(new ComponentName("package0", "class0"), new UserHandle(3))));
        mModel.setCurrentUser(4);
        assertEquals(null, mModel.buildAppLaunchIntent(
                new AppInfo(new ComponentName("package1", "class1"), new UserHandle(4))));
        mModel.setCurrentUser(5);
        assertEquals(null, mModel.buildAppLaunchIntent(
                new AppInfo(new ComponentName("package2", "class2"), new UserHandle(5))));
        mModel.setCurrentUser(6);
        assertEquals(null, mModel.buildAppLaunchIntent(
                new AppInfo(new ComponentName("package3", "class3"), new UserHandle(6))));

        // A launcheable app.
        mModel.setCurrentUser(7);
        Intent intent = mModel.buildAppLaunchIntent(
                new AppInfo(new ComponentName("package4", "class4"), new UserHandle(7)));
        assertNotNull(intent);
        assertEquals(new ComponentName("package4", "class4"), intent.getComponent());
        assertEquals("package4", intent.getPackage());
    }

    /** Initializes the model from SharedPreferences for a few app activites. */
    private void initializeModelFromPrefs() {
        // Assume several apps are stored.
        when(mMockPrefs.getInt("222|app_count", -1)).thenReturn(2);
        when(mMockPrefs.getString("222|app_0", null)).thenReturn("package1/class1");
        when(mMockPrefs.getLong("222|app_user_0", -1)).thenReturn(444L);
        when(mMockPrefs.getString("222|app_1", null)).thenReturn("package2/class2");
        when(mMockPrefs.getLong("222|app_user_1", -1)).thenReturn(555L);

        ActivityInfo mockActivityInfo = new ActivityInfo();
        mockActivityInfo.exported = true;
        try {
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package0", "class0"), 0, 5)).thenReturn(mockActivityInfo);
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package1", "class1"), 0, 4)).thenReturn(mockActivityInfo);
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package2", "class2"), 0, 5)).thenReturn(mockActivityInfo);
        } catch (RemoteException e) {
            fail("RemoteException can't happen in the test, but it happened.");
        }

        // Assume some installed activities.
        ActivityInfo ai0 = new ActivityInfo();
        ai0.packageName = "package0";
        ai0.name = "class0";
        ActivityInfo ai1 = new ActivityInfo();
        ai1.packageName = "package1";
        ai1.name = "class1";
        ActivityInfo ai2 = new ActivityInfo();
        ai2.packageName = "package2";
        ai2.name = "class2";
        ResolveInfo ri0 = new ResolveInfo();
        ri0.activityInfo = ai0;
        ResolveInfo ri1 = new ResolveInfo();
        ri1.activityInfo = ai1;
        ResolveInfo ri2 = new ResolveInfo();
        ri2.activityInfo = ai2;
        when(mMockPackageManager
                .queryIntentActivitiesAsUser(any(Intent.class), eq(0), any(int.class)))
                .thenReturn(Arrays.asList(ri0, ri1, ri2));

        mModel.setCurrentUser(2);
    }

    /** Tests initializing the model from SharedPreferences. */
    public void testInitializeFromPrefs() {
        initializeModelFromPrefs();
        List<AppInfo> apps = mModel.getApps();
        assertEquals(2, apps.size());
        assertEquals("package1/class1", apps.get(0).getComponentName().flattenToString());
        assertEquals(new UserHandle(4), apps.get(0).getUser());
        assertEquals("package2/class2", apps.get(1).getComponentName().flattenToString());
        assertEquals(new UserHandle(5), apps.get(1).getUser());
    }

    /** Tests initializing the model when the SharedPreferences aren't available. */
    public void testInitializeDefaultApps() {
        // Assume the user's app count pref isn't available.
        when(mMockPrefs.getInt("222|app_count", -1)).thenReturn(-1);

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
        List<AppInfo> apps = mModel.getApps();
        assertEquals(2, apps.size());
        assertEquals("package1/class1", apps.get(0).getComponentName().flattenToString());
        assertEquals(new UserHandle(2), apps.get(0).getUser());
        assertEquals("package2/class2", apps.get(1).getComponentName().flattenToString());
        assertEquals(new UserHandle(2), apps.get(1).getUser());
        InOrder order = inOrder(mMockEdit);
        order.verify(mMockEdit).apply();
        order.verify(mMockEdit).putInt("222|app_count", 2);
        order.verify(mMockEdit).putString("222|app_0", "package1/class1");
        order.verify(mMockEdit).putLong("222|app_user_0", 222L);
        order.verify(mMockEdit).putString("222|app_1", "package2/class2");
        order.verify(mMockEdit).putLong("222|app_user_1", 222L);
        order.verify(mMockEdit).apply();
        verifyNoMoreInteractions(mMockEdit);
    }

    /** Tests initializing the model if one of the prefs is missing. */
    public void testInitializeWithMissingPref() {
        // Assume two apps are nominally stored.
        when(mMockPrefs.getInt("222|app_count", -1)).thenReturn(2);
        when(mMockPrefs.getString("222|app_0", null)).thenReturn("package0/class0");
        when(mMockPrefs.getLong("222|app_user_0", -1)).thenReturn(555L);

        // But assume one pref is missing.
        when(mMockPrefs.getString("222|app_1", null)).thenReturn(null);

        ActivityInfo mockActivityInfo = new ActivityInfo();
        mockActivityInfo.exported = true;
        try {
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package0", "class0"), 0, 5)).thenReturn(mockActivityInfo);
        } catch (RemoteException e) {
            fail("RemoteException can't happen in the test, but it happened.");
        }

        ActivityInfo ai0 = new ActivityInfo();
        ai0.packageName = "package0";
        ai0.name = "class0";
        ResolveInfo ri0 = new ResolveInfo();
        ri0.activityInfo = ai0;
        when(mMockPackageManager
                .queryIntentActivitiesAsUser(any(Intent.class), eq(0), any(int.class)))
                .thenReturn(Arrays.asList(ri0));

        // Initializing the model should load from prefs and skip the missing one.
        mModel.setCurrentUser(2);
        List<AppInfo> apps = mModel.getApps();
        assertEquals(1, apps.size());
        assertEquals("package0/class0", apps.get(0).getComponentName().flattenToString());
        assertEquals(new UserHandle(5), apps.get(0).getUser());
        InOrder order = inOrder(mMockEdit);
        order.verify(mMockEdit).putInt("222|app_count", 1);
        order.verify(mMockEdit).putString("222|app_0", "package0/class0");
        order.verify(mMockEdit).putLong("222|app_user_0", 555L);
        order.verify(mMockEdit).apply();
        verifyNoMoreInteractions(mMockEdit);
    }

    /** Tests initializing the model if one of the apps is unlauncheable. */
    public void testInitializeWithUnlauncheableApp() {
        // Assume two apps are nominally stored.
        when(mMockPrefs.getInt("222|app_count", -1)).thenReturn(2);
        when(mMockPrefs.getString("222|app_0", null)).thenReturn("package0/class0");
        when(mMockPrefs.getLong("222|app_user_0", -1)).thenReturn(555L);
        when(mMockPrefs.getString("222|app_1", null)).thenReturn("package1/class1");
        when(mMockPrefs.getLong("222|app_user_1", -1)).thenReturn(444L);

        ActivityInfo mockActivityInfo = new ActivityInfo();
        mockActivityInfo.exported = true;
        try {
            when(mMockIPackageManager.getActivityInfo(
                    new ComponentName("package0", "class0"), 0, 5)).thenReturn(mockActivityInfo);
        } catch (RemoteException e) {
            fail("RemoteException can't happen in the test, but it happened.");
        }

        ActivityInfo ai0 = new ActivityInfo();
        ai0.packageName = "package0";
        ai0.name = "class0";
        ResolveInfo ri0 = new ResolveInfo();
        ri0.activityInfo = ai0;
        when(mMockPackageManager
                .queryIntentActivitiesAsUser(any(Intent.class), eq(0), any(int.class)))
                .thenReturn(Arrays.asList(ri0));

        // Initializing the model should load from prefs and skip the unlauncheable one.
        mModel.setCurrentUser(2);
        List<AppInfo> apps = mModel.getApps();
        assertEquals(1, apps.size());
        assertEquals("package0/class0", apps.get(0).getComponentName().flattenToString());
        assertEquals(new UserHandle(5), apps.get(0).getUser());

        // Once an unlauncheable app is detected, the model should save all apps excluding the
        // unlauncheable one.
        verify(mMockEdit).putInt("222|app_count", 1);
        verify(mMockEdit).putString("222|app_0", "package0/class0");
        verify(mMockEdit).putLong("222|app_user_0", 555L);
        verify(mMockEdit).apply();
        verifyNoMoreInteractions(mMockEdit);
    }

    /** Tests saving the model to SharedPreferences. */
    public void testSavePrefs() {
        initializeModelFromPrefs();

        mModel.setApps(mModel.getApps());
        verify(mMockEdit).putInt("222|app_count", 2);
        verify(mMockEdit).putString("222|app_0", "package1/class1");
        verify(mMockEdit).putLong("222|app_user_0", 444L);
        verify(mMockEdit).putString("222|app_1", "package2/class2");
        verify(mMockEdit).putLong("222|app_user_1", 555L);
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
        when(mMockPrefs.getInt("222|app_count", -1)).thenReturn(-1);

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

    /** Tests the apps-changed listener. */
    public void testAppsChangedListeners() {
        NavigationBarAppsModel.OnAppsChangedListener listener =
                mock(NavigationBarAppsModel.OnAppsChangedListener.class);

        mModel.addOnAppsChangedListener(listener);
        mModel.setApps(new ArrayList<AppInfo>());
        verify(listener).onPinnedAppsChanged();
        verifyNoMoreInteractions(listener);

        mModel.removeOnAppsChangedListener(listener);
        mModel.setApps(new ArrayList<AppInfo>());
        verifyNoMoreInteractions(listener);
    }
}
