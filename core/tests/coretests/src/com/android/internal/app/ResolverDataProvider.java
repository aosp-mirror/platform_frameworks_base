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
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;

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

    static Intent createResolverIntent(int i) {
        return new Intent("intentAction" + i);
    }
}