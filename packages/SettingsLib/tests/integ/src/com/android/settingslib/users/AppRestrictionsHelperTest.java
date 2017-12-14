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
 * limitations under the License
 */

package com.android.settingslib.users;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.inputmethod.InputMethodInfo;
import com.android.settingslib.BaseTest;

import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
public class AppRestrictionsHelperTest extends BaseTest {
    private @Mock Context mContext;
    private @Mock PackageManager mPm;
    private @Mock IPackageManager mIpm;
    private @Mock UserManager mUm;

    private TestInjector mInjector;
    private UserHandle mTestUser = UserHandle.of(1111);
    private AppRestrictionsHelper mHelper;

    private ArrayList<String> mInstalledApps;
    private ArrayList<ApplicationInfo> mInstalledAppInfos;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);

        mInjector = new TestInjector();
        final UserInfo user = new UserInfo(
                mTestUser.getIdentifier(), "test_user", UserInfo.FLAG_RESTRICTED);
        when(mUm.getUserInfo(mTestUser.getIdentifier())).thenReturn(user);
        mHelper = new AppRestrictionsHelper(mInjector);
        mInstalledApps = new ArrayList<>();
        mInstalledAppInfos = new ArrayList<>();
    }

    public void testFetchAndMergeApps() throws Exception {
        addSystemAppsWithRequiredAccounts("sys.app0");
        addsystemImes(new String[] {"sys.app1", "sys.app2"},
                new String[] {"sys.app3", "sys.app4", "sys.app5"});
        addSystemAppsForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                "sys.app1", "sys.app4", "sys.app6");
        addSystemAppsForIntent(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
                "sys.app2", "sys.app5", "sys.app7");
        addDownloadedApps("app1", "app2");
        when(mPm.getInstalledApplications(anyInt())).thenReturn(mInstalledAppInfos);

        mHelper.fetchAndMergeApps();

        final ArrayList<String> notExpectedInVisibleApps = new ArrayList<>();
        // System apps that require an account and doesn't see restricted account are
        // not part of visibleApps.
        notExpectedInVisibleApps.add("sys.app0");
        // Default system IMEs are not part of visibleApps.
        notExpectedInVisibleApps.add("sys.app1");
        notExpectedInVisibleApps.add("sys.app2");

        final ArrayList<String> expectedInVisibleApps = new ArrayList<>();
        expectedInVisibleApps.add("sys.app4");
        expectedInVisibleApps.add("sys.app5");
        expectedInVisibleApps.add("sys.app6");
        expectedInVisibleApps.add("sys.app7");
        expectedInVisibleApps.add("app1");
        expectedInVisibleApps.add("app2");

        for (AppRestrictionsHelper.SelectableAppInfo info : mHelper.getVisibleApps()) {
            if (expectedInVisibleApps.contains(info.packageName)) {
                expectedInVisibleApps.remove(info.packageName);
            } else if (notExpectedInVisibleApps.contains(info.packageName)) {
                fail("Package: " + info.packageName + " should not be included in visibleApps");
            } else {
                fail("Unknown package: " + info.packageName);
            }
        }
        assertEquals("Some expected apps are not inclued in visibleApps: " + expectedInVisibleApps,
                0, expectedInVisibleApps.size());

        assertFalse("System apps that require an account and doesn't see restricted account "
                + "should be marked for removal", mHelper.isPackageSelected("sys.app0"));
    }

    public void testApplyUserAppsStates() throws Exception {
        final int testUserId = mTestUser.getIdentifier();
        mHelper.setPackageSelected("app1", true);

        mHelper.setPackageSelected("app2", true);
        ApplicationInfo info = new ApplicationInfo();
        info.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        info.flags |= ApplicationInfo.FLAG_INSTALLED;
        when(mIpm.getApplicationInfo(eq("app2"), anyInt(), eq(testUserId)))
                .thenReturn(info);

        mHelper.setPackageSelected("app3", false);
        info = new ApplicationInfo();
        when(mIpm.getApplicationInfo(eq("app3"), anyInt(), eq(testUserId)))
                .thenReturn(info);

        AppRestrictionsHelper.OnDisableUiForPackageListener mockListener =
                mock(AppRestrictionsHelper.OnDisableUiForPackageListener.class);
        mHelper.applyUserAppsStates(mockListener);

        verify(mIpm, times(1)).installExistingPackageAsUser("app1", testUserId,
                0 /*installFlags*/, PackageManager.INSTALL_REASON_UNKNOWN);
        verify(mIpm, times(1)).setApplicationHiddenSettingAsUser("app2", false, testUserId);
        verify(mockListener).onDisableUiForPackage("app2");
        verify(mPm, times(1)).deletePackageAsUser(eq("app3"),
                nullable(IPackageDeleteObserver.class), anyInt(), eq(mTestUser.getIdentifier()));
    }

    private void addsystemImes(String[] defaultImes, String[] otherImes) throws
            PackageManager.NameNotFoundException, RemoteException {
        final ArrayList<InputMethodInfo> inputMethods = new ArrayList<>();
        for (String pkg : defaultImes) {
            final ResolveInfo ri = createResolveInfoForSystemApp(pkg);
            final InputMethodInfo inputMethodInfo = new InputMethodInfo(
                    ri, false, null, null, 0, true, true, false);
            inputMethods.add(inputMethodInfo);
            addInstalledApp(ri);
        }
        for (String pkg : otherImes) {
            final ResolveInfo ri = createResolveInfoForSystemApp(pkg);
            final InputMethodInfo inputMethodInfo = new InputMethodInfo(
                    ri, false, null, null, 0, false, true, false);
            inputMethods.add(inputMethodInfo);
            addInstalledApp(ri);
        }

        mInjector.setInputMethodList(inputMethods);
    }

    private void addSystemAppsForIntent(Intent intent, String... packages) throws Exception {
        List<ResolveInfo> resolveInfos = new ArrayList<>();
        for (String pkg : packages) {
            final ResolveInfo ri = createResolveInfoForSystemApp(pkg);
            resolveInfos.add(ri);
            addInstalledApp(ri);
        }
        when(mPm.queryIntentActivities(argThat(new IntentMatcher(intent)), anyInt()))
                .thenReturn(resolveInfos);
    }

    private void addSystemAppsWithRequiredAccounts(String... packages) throws Exception {
        for (String pkg : packages) {
            final ResolveInfo ri = createResolveInfoForSystemApp(pkg);
            final PackageInfo packageInfo = new PackageInfo();
            packageInfo.applicationInfo = ri.activityInfo.applicationInfo;
            packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
            packageInfo.requiredAccountType = "account";
            packageInfo.restrictedAccountType = null;
            mInstalledAppInfos.add(packageInfo.applicationInfo);
            when(mPm.getPackageInfo(eq(pkg), anyInt())).thenReturn(packageInfo);
        }
    }

    private void addDownloadedApps(String... packages) throws Exception {
        for (String pkg : packages) {
            final ResolveInfo ri = createResolveInfo(pkg);
            addInstalledApp(ri);
        }
    }

    private void addInstalledApp(ResolveInfo ri) throws PackageManager.NameNotFoundException {
        final String pkgName = ri.activityInfo.packageName;
        if (mInstalledApps.contains(pkgName)) {
            return;
        }
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = ri.activityInfo.applicationInfo;
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        mInstalledAppInfos.add(packageInfo.applicationInfo);
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

    private class IntentMatcher implements ArgumentMatcher<Intent> {
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

    private class TestInjector extends AppRestrictionsHelper.Injector {
        List<InputMethodInfo> mImis;

        TestInjector() {
            super(mContext, mTestUser);
        }

        @Override
        Context getContext() {
            return mContext;
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

        @Override
        UserManager getUserManager() {
            return mUm;
        }

        @Override
        List<InputMethodInfo> getInputMethodList() {
            return mImis;
        }

        void setInputMethodList(List<InputMethodInfo> imis) {
            mImis = imis;
        }
    }
}
