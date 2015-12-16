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
 * limitations under the License
 */

package android.content.pm;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.view.inputmethod.InputMethodInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class AppsQueryHelperTests extends AndroidTestCase {

    private AppsQueryHelper mAppsQueryHelper;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mAppsQueryHelper = new AppsQueryHelperTestable();
    }

    public void testQueryAppsSystemAppsOnly() {
        List<String> apps = mAppsQueryHelper.queryApps(0, true, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1", "sys_app2", "sys_app3"), apps);

        apps = mAppsQueryHelper.queryApps(0, false, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1", "sys_app2", "sys_app3", "app4"), apps);
    }

    public void testQueryAppsNonLaunchable() {
        List<String> apps = mAppsQueryHelper.queryApps(AppsQueryHelper.GET_NON_LAUNCHABLE_APPS,
                true, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1", "sys_app3"), apps);

        apps = mAppsQueryHelper.queryApps(AppsQueryHelper.GET_NON_LAUNCHABLE_APPS,
                false, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1", "sys_app3"), apps);
    }

    public void testQueryAppsInteractAcrossUser() {
        List<String> apps = mAppsQueryHelper.queryApps(
                AppsQueryHelper.GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM, true, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1"), apps);

        apps = mAppsQueryHelper.queryApps(
                AppsQueryHelper.GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM, false, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1"), apps);
    }

    public void testQueryApps() {
        List<String> apps = mAppsQueryHelper.queryApps(
                AppsQueryHelper.GET_NON_LAUNCHABLE_APPS
                        |AppsQueryHelper.GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM,
                true, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1", "sys_app3"), apps);

        apps = mAppsQueryHelper.queryApps(
                AppsQueryHelper.GET_NON_LAUNCHABLE_APPS
                        |AppsQueryHelper.GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM,
                false, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1", "sys_app3"), apps);
    }

    public void testQueryAppsImes() {
        // Test query system IMEs
        List<String> apps = mAppsQueryHelper.queryApps(AppsQueryHelper.GET_IMES,
                true, UserHandle.of(UserHandle.myUserId()));
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1"), apps);

        // Test query IMEs
        apps = mAppsQueryHelper.queryApps(AppsQueryHelper.GET_IMES, false,
                UserHandle.of(UserHandle.myUserId()));
        assertEqualsIgnoreOrder(Arrays.asList("sys_app1", "app4"), apps);
    }

    public void testQueryAppsRequiredForSystemUser() {
        // Test query only system apps required for system user
        List<String> apps = mAppsQueryHelper.queryApps(AppsQueryHelper.GET_REQUIRED_FOR_SYSTEM_USER,
                true, UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app3"), apps);

        // Test query all apps required for system user
        apps = mAppsQueryHelper.queryApps(AppsQueryHelper.GET_REQUIRED_FOR_SYSTEM_USER, false,
                UserHandle.SYSTEM);
        assertEqualsIgnoreOrder(Arrays.asList("sys_app3", "app4"), apps);
    }

    private class AppsQueryHelperTestable extends AppsQueryHelper {

        @Override
        protected List<ApplicationInfo> getAllApps(int userId) {
            final ApplicationInfo ai1 = new ApplicationInfo();
            ai1.flags |= ApplicationInfo.FLAG_SYSTEM;
            ai1.packageName = "sys_app1";
            final ApplicationInfo ai2 = new ApplicationInfo();
            ai2.flags |= ApplicationInfo.FLAG_SYSTEM;
            ai2.packageName = "sys_app2";
            ai2.flags |= ApplicationInfo.FLAG_SYSTEM;
            final ApplicationInfo ai3 = new ApplicationInfo();
            ai3.packageName = "sys_app3";
            ai3.flags |= ApplicationInfo.FLAG_SYSTEM;
            ai3.privateFlags |= ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER;
            final ApplicationInfo ai4 = new ApplicationInfo();
            ai4.privateFlags |= ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER;
            ai4.packageName = "app4";
            return Arrays.asList(ai1, ai2, ai3, ai4);
        }

        @Override
        protected List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int userId) {
            assertEquals(Intent.CATEGORY_LAUNCHER, intent.getCategories().iterator().next());
            final ResolveInfo r2 = new ResolveInfo();
            r2.activityInfo = new ActivityInfo();
            r2.activityInfo.packageName = "sys_app2";
            r2.activityInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
            final ResolveInfo r4 = new ResolveInfo();
            r4.activityInfo = new ActivityInfo();
            r4.activityInfo.packageName = "app4";
            return Arrays.asList(r2, r4);
        }

        @Override
        protected List<PackageInfo> getPackagesHoldingPermission(String perm, int userId) {
            final PackageInfo p1 = new PackageInfo();
            p1.packageName = "sys_app1";
            p1.applicationInfo = new ApplicationInfo();
            p1.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
            return Arrays.asList(p1);
        }

        @Override
        protected List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int userId) {
            final ResolveInfo sysApp1 = new ResolveInfo();
            sysApp1.serviceInfo = new ServiceInfo();
            sysApp1.serviceInfo.packageName = "sys_app1";
            sysApp1.serviceInfo.name = "name";
            sysApp1.serviceInfo.applicationInfo = new ApplicationInfo();
            sysApp1.serviceInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
            final ResolveInfo app4 = new ResolveInfo();
            app4.serviceInfo = new ServiceInfo();
            app4.serviceInfo.packageName = "app4";
            app4.serviceInfo.name = "name";
            app4.serviceInfo.applicationInfo = new ApplicationInfo();
            return Arrays.asList(sysApp1, app4);
        }
    }

    private static void assertEqualsIgnoreOrder(List<String> expected, List<String> actual) {
        assertTrue("Lists not equal. Expected " + expected + " but was " + actual,
                (expected.size() == actual.size())
                        && (new HashSet<>(expected).equals(new HashSet<>(actual))));
    }
}
