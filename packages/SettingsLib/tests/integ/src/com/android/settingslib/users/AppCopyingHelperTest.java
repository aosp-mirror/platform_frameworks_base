/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.users;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.settingslib.BaseTest;

import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for AppCopyHelper.
 */
@SmallTest
public class AppCopyingHelperTest extends BaseTest {
    private @Mock Context mContext;
    private @Mock PackageManager mPm;
    private @Mock IPackageManager mIpm;

    private final UserHandle mTestUser = UserHandle.of(1111);
    private AppCopyHelper mHelper;

    private final ArrayList<ApplicationInfo> mCurrUserInstalledAppInfos = new ArrayList<>();
    private final ArrayList<ApplicationInfo> mTestUserInstalledAppInfos = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mHelper = new AppCopyHelper(new TestInjector());
    }

    public void testFetchAndMergeApps() throws Exception {
        // Apps on the current user.
        final String[] sysInapplicables = new String[] {"sys.no0, sys.no1"};
        final String[] sysLaunchables = new String[] {"sys1", "sys2", "sys3"};
        final String[] sysWidgets = new String[] {"sys1", "sys4"};
        final String[] downloadeds = new String[] {"app1", "app2"};

        addInapplicableSystemApps(sysInapplicables);
        addSystemAppsForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                sysLaunchables);
        addSystemAppsForIntent(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
                sysWidgets);
        addDownloadedApps(downloadeds);
        when(mPm.getInstalledApplications(anyInt())).thenReturn(mCurrUserInstalledAppInfos);

        // Apps on the test user.
        final String[] testUserApps =
                new String[]{"sys.no0", "sys2", "sys4", "app2", "sys999", "app999"};
        addAppsToTestUser(testUserApps);
        when(mPm.getInstalledApplicationsAsUser(anyInt(), eq(mTestUser.getIdentifier())))
                .thenReturn(mTestUserInstalledAppInfos);

        mHelper.fetchAndMergeApps();

        final ArraySet<String> notExpectedInVisibleApps = new ArraySet<>();
        Collections.addAll(notExpectedInVisibleApps, sysInapplicables);
        Collections.addAll(notExpectedInVisibleApps, testUserApps);

        final ArraySet<String> expectedInVisibleApps = new ArraySet<>();
        Collections.addAll(expectedInVisibleApps, sysLaunchables);
        Collections.addAll(expectedInVisibleApps, sysWidgets);
        Collections.addAll(expectedInVisibleApps, downloadeds);
        expectedInVisibleApps.removeAll(notExpectedInVisibleApps);

        for (AppCopyHelper.SelectableAppInfo info : mHelper.getVisibleApps()) {
            if (expectedInVisibleApps.contains(info.packageName)) {
                expectedInVisibleApps.remove(info.packageName);
            } else if (notExpectedInVisibleApps.contains(info.packageName)) {
                fail("Package: " + info.packageName + " should not be included in visibleApps");
            } else {
                fail("Unknown package: " + info.packageName);
            }
        }
        assertEquals("Some expected apps are not included in visibleApps: " + expectedInVisibleApps,
                0, expectedInVisibleApps.size());
    }

    public void testInstallSelectedApps() throws Exception {
        final int testUserId = mTestUser.getIdentifier();

        mHelper.setPackageSelected("app1", true); // Ultimately true
        mHelper.setPackageSelected("app2", true); // Ultimately false
        mHelper.setPackageSelected("app3", true); // Ultimately true
        mHelper.setPackageSelected("app4", true); // Ultimately true

        mHelper.setPackageSelected("app2", false);
        mHelper.setPackageSelected("app1", false);
        mHelper.setPackageSelected("app1", true);


        // app3 is installed but hidden
        ApplicationInfo info = new ApplicationInfo();
        info.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        info.flags |= ApplicationInfo.FLAG_INSTALLED;
        when(mIpm.getApplicationInfo(eq("app3"), anyLong(), eq(testUserId)))
                .thenReturn(info);

        info = new ApplicationInfo();
        when(mIpm.getApplicationInfo(eq("app4"), anyLong(), eq(testUserId)))
                .thenReturn(info);

        mHelper.installSelectedApps();

        verify(mIpm, times(1)).installExistingPackageAsUser(
                "app1", testUserId,
                PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                PackageManager.INSTALL_REASON_UNKNOWN, null);
        verify(mIpm, times(0)).installExistingPackageAsUser(eq(
                "app2"), eq(testUserId),
                anyInt(), anyInt(), any());
        verify(mIpm, times(0)).installExistingPackageAsUser(eq(
                "app3"), eq(testUserId),
                anyInt(), anyInt(), any());
        verify(mIpm, times(1)).installExistingPackageAsUser(
                "app4", testUserId,
                PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                PackageManager.INSTALL_REASON_UNKNOWN, null);

        verify(mIpm, times(0)).setApplicationHiddenSettingAsUser(
                eq("app1"), anyBoolean(), eq(testUserId));
        verify(mIpm, times(0)).setApplicationHiddenSettingAsUser(
                eq("app2"), anyBoolean(), eq(testUserId));
        verify(mIpm, times(1)).setApplicationHiddenSettingAsUser(
                eq("app3"), eq(false), eq(testUserId));
        verify(mIpm, times(0)).setApplicationHiddenSettingAsUser(
                eq("app4"), anyBoolean(), eq(testUserId));
    }

    private void addSystemAppsForIntent(Intent intent, String... packages) throws Exception {
        final List<ResolveInfo> resolveInfos = new ArrayList<>();
        for (String pkg : packages) {
            final ResolveInfo ri = createResolveInfoForSystemApp(pkg);
            resolveInfos.add(ri);
            addInstalledApp(ri, false);
        }
        when(mPm.queryIntentActivities(argThat(new IntentMatcher(intent)), anyInt()))
                .thenReturn(resolveInfos);
    }

    private void addInapplicableSystemApps(String... packages) throws Exception {
        for (String pkg : packages) {
            final ResolveInfo ri = createResolveInfoForSystemApp(pkg);
            addInstalledApp(ri, false);
        }
    }

    private void addDownloadedApps(String... packages) throws Exception {
        for (String pkg : packages) {
            final ResolveInfo ri = createResolveInfo(pkg);
            addInstalledApp(ri, false);
        }
    }

    private void addAppsToTestUser(String... packages) throws Exception {
        for (String pkg : packages) {
            final ResolveInfo ri = createResolveInfo(pkg);
            addInstalledApp(ri, true);
        }
    }

    private void addInstalledApp(ResolveInfo ri, boolean testUser)
            throws PackageManager.NameNotFoundException {
        final String pkgName = ri.activityInfo.packageName;
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = ri.activityInfo.applicationInfo;
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        if (testUser) {
            mTestUserInstalledAppInfos.add(packageInfo.applicationInfo);
        } else {
            mCurrUserInstalledAppInfos.add(packageInfo.applicationInfo);
        }
        when(mPm.getPackageInfo(eq(pkgName), anyInt())).thenReturn(packageInfo);
    }

    private ResolveInfo createResolveInfoForSystemApp(String packageName) {
        final ResolveInfo ri = createResolveInfo(packageName);
        ri.activityInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        ri.serviceInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        return ri;
    }

    private ResolveInfo createResolveInfo(String packageName) {
        final ResolveInfo ri = new ResolveInfo();
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        final ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.applicationInfo = applicationInfo;
        activityInfo.packageName = packageName;
        activityInfo.name = "";
        ri.activityInfo = activityInfo;
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.applicationInfo = applicationInfo;
        serviceInfo.packageName = packageName;
        serviceInfo.name = "";
        ri.serviceInfo = serviceInfo;
        return ri;
    }

    private static class IntentMatcher implements ArgumentMatcher<Intent> {
        private final Intent mIntent;

        IntentMatcher(Intent intent) {
            mIntent = intent;
        }

        @Override
        public boolean matches(Intent argument) {
            return argument != null && argument.filterEquals(mIntent);
        }

        @Override
        public String toString() {
            return "Expected: " + mIntent;
        }
    }

    private class TestInjector extends AppCopyHelper.Injector {
        TestInjector() {
            super(mContext, mTestUser);
        }

        @Override
        UserHandle getUser() {
            return mTestUser;
        }

        @Override
        PackageManager getPackageManager() {
            return mPm;
        }

        @Override
        IPackageManager getIPackageManager() {
            return mIpm;
        }
    }
}
