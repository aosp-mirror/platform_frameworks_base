/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;
import android.test.mock.MockResources;

/**
 * Utility class used by resolver tests to create mock data
 */
class ResolverDataProvider {

    static private int USER_SOMEONE_ELSE = 10;

    static ResolverActivity.ResolvedComponentInfo createResolvedComponentInfo(int i) {
        return new ResolverActivity.ResolvedComponentInfo(createComponentName(i),
                createResolverIntent(i), createResolveInfo(i, UserHandle.USER_CURRENT));
    }

    static ResolverActivity.ResolvedComponentInfo createResolvedComponentInfoWithOtherId(int i) {
        return new ResolverActivity.ResolvedComponentInfo(createComponentName(i),
                createResolverIntent(i), createResolveInfo(i, USER_SOMEONE_ELSE));
    }

    static ComponentName createComponentName(int i) {
        final String name = "component" + i;
        return new ComponentName("foo.bar." + name, name);
    }

    static ResolveInfo createResolveInfo(int i, int userId) {
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = createActivityInfo(i);
        resolveInfo.targetUserId = userId;
        return resolveInfo;
    }

    static ActivityInfo createActivityInfo(int i) {
        ActivityInfo ai = new ActivityInfo();
        ai.name = "activity_name" + i;
        ai.packageName = "foo_bar" + i;
        ai.enabled = true;
        ai.exported = true;
        ai.permission = null;
        ai.applicationInfo = createApplicationInfo();
        return ai;
    }

    static ApplicationInfo createApplicationInfo() {
        ApplicationInfo ai = new ApplicationInfo();
        ai.name = "app_name";
        ai.packageName = "foo.bar";
        ai.enabled = true;
        return ai;
    }

    static class PackageManagerMockedInfo {
        public Context ctx;
        public ApplicationInfo appInfo;
        public ActivityInfo activityInfo;
        public ResolveInfo resolveInfo;
        public String setAppLabel;
        public String setActivityLabel;
        public String setResolveInfoLabel;
    }

    static PackageManagerMockedInfo createPackageManagerMockedInfo(boolean hasOverridePermission) {
        final String appLabel = "app_label";
        final String activityLabel = "activity_label";
        final String resolveInfoLabel = "resolve_info_label";

        MockContext ctx = new MockContext() {
            @Override
            public PackageManager getPackageManager() {
                return new MockPackageManager() {
                    @Override
                    public int checkPermission(String permName, String pkgName) {
                        if (hasOverridePermission) return PERMISSION_GRANTED;
                        return PERMISSION_DENIED;
                    }
                };
            }

            @Override
            public Resources getResources() {
                return new MockResources() {
                    @Override
                    public String getString(int id) throws NotFoundException {
                        if (id == 1) return appLabel;
                        if (id == 2) return activityLabel;
                        if (id == 3) return resolveInfoLabel;
                        return null;
                    }
                };
            }
        };

        ApplicationInfo appInfo = new ApplicationInfo() {
            @Override
            public CharSequence loadLabel(PackageManager pm) {
                return appLabel;
            }
        };
        appInfo.labelRes = 1;

        ActivityInfo activityInfo = new ActivityInfo() {
            @Override
            public CharSequence loadLabel(PackageManager pm) {
                return activityLabel;
            }
        };
        activityInfo.labelRes = 2;
        activityInfo.applicationInfo = appInfo;

        ResolveInfo resolveInfo = new ResolveInfo() {
            @Override
            public CharSequence loadLabel(PackageManager pm) {
                return resolveInfoLabel;
            }
        };
        resolveInfo.activityInfo = activityInfo;
        resolveInfo.resolvePackageName = "super.fake.packagename";
        resolveInfo.labelRes = 3;

        PackageManagerMockedInfo mockedInfo = new PackageManagerMockedInfo();
        mockedInfo.activityInfo = activityInfo;
        mockedInfo.appInfo = appInfo;
        mockedInfo.ctx = ctx;
        mockedInfo.resolveInfo = resolveInfo;
        mockedInfo.setAppLabel = appLabel;
        mockedInfo.setActivityLabel = activityLabel;
        mockedInfo.setResolveInfoLabel = resolveInfoLabel;

        return mockedInfo;
    }

    static Intent createResolverIntent(int i) {
        return new Intent("intentAction" + i);
    }
}