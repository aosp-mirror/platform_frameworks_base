/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.pm;

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ICrossProfileApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.ArrayList;
import java.util.List;

public class CrossProfileAppsServiceImpl extends ICrossProfileApps.Stub {
    private static final String TAG = "CrossProfileAppsService";

    private Context mContext;
    private Injector mInjector;

    public CrossProfileAppsServiceImpl(Context context) {
        this(context, new InjectorImpl(context));
    }

    @VisibleForTesting
    CrossProfileAppsServiceImpl(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
    }

    @Override
    public List<UserHandle> getTargetUserProfiles(String callingPackage) {
        Preconditions.checkNotNull(callingPackage);

        verifyCallingPackage(callingPackage);

        return getTargetUserProfilesUnchecked(
                callingPackage, mInjector.getCallingUserId());
    }

    @Override
    public void startActivityAsUser(
            IApplicationThread caller,
            String callingPackage,
            ComponentName component,
            @UserIdInt int userId,
            boolean launchMainActivity) throws RemoteException {
        Preconditions.checkNotNull(callingPackage);
        Preconditions.checkNotNull(component);

        verifyCallingPackage(callingPackage);

        final int callerUserId = mInjector.getCallingUserId();
        final int callingUid = mInjector.getCallingUid();

        List<UserHandle> allowedTargetUsers = getTargetUserProfilesUnchecked(
                callingPackage, callerUserId);
        if (!allowedTargetUsers.contains(UserHandle.of(userId))) {
            throw new SecurityException(callingPackage + " cannot access unrelated user " + userId);
        }

        // Verify that caller package is starting activity in its own package.
        if (!callingPackage.equals(component.getPackageName())) {
            throw new SecurityException(
                    callingPackage + " attempts to start an activity in other package - "
                            + component.getPackageName());
        }

        // Verify that target activity does handle the intent correctly.
        final Intent launchIntent = new Intent();
        if (launchMainActivity) {
            launchIntent.setAction(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // Only package name is set here, as opposed to component name, because intent action
            // and category are ignored if component name is present while we are resolving intent.
            launchIntent.setPackage(component.getPackageName());
        } else {
            // If the main activity is not being launched and the users are different, the caller
            // must have the required permission and the users must be in the same profile group
            // in order to launch any of its own activities.
            if (callerUserId != userId) {
                final int permissionFlag = ActivityManager.checkComponentPermission(
                        android.Manifest.permission.INTERACT_ACROSS_PROFILES, callingUid,
                        -1, true);
                if (permissionFlag != PackageManager.PERMISSION_GRANTED
                        || !isSameProfileGroup(callerUserId, userId)) {
                    throw new SecurityException("Attempt to launch activity without required "
                            + android.Manifest.permission.INTERACT_ACROSS_PROFILES + " permission"
                            + " or target user is not in the same profile group.");
                }
            }
            launchIntent.setComponent(component);
        }
        verifyActivityCanHandleIntentAndExported(launchIntent, component, callingUid, userId);

        launchIntent.setPackage(null);
        launchIntent.setComponent(component);
        mInjector.getActivityTaskManagerInternal().startActivityAsUser(
                caller, callingPackage, launchIntent,
                launchMainActivity
                        ? ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle()
                        : null,
                userId);
    }

    private List<UserHandle> getTargetUserProfilesUnchecked(
            String callingPackage, @UserIdInt int callingUserId) {
        final long ident = mInjector.clearCallingIdentity();
        try {
            final int[] enabledProfileIds =
                    mInjector.getUserManager().getEnabledProfileIds(callingUserId);

            List<UserHandle> targetProfiles = new ArrayList<>();
            for (final int userId : enabledProfileIds) {
                if (userId == callingUserId) {
                    continue;
                }
                if (!isPackageEnabled(callingPackage, userId)) {
                    continue;
                }
                targetProfiles.add(UserHandle.of(userId));
            }
            return targetProfiles;
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    private boolean isPackageEnabled(String packageName, @UserIdInt int userId) {
        final int callingUid = mInjector.getCallingUid();
        final long ident = mInjector.clearCallingIdentity();
        try {
            final PackageInfo info = mInjector.getPackageManagerInternal()
                    .getPackageInfo(
                            packageName,
                            MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                            callingUid,
                            userId);
            return info != null && info.applicationInfo.enabled;
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    /**
     * Verify that the specified intent does resolved to the specified component and the resolved
     * activity is exported.
     */
    private void verifyActivityCanHandleIntentAndExported(
            Intent launchIntent, ComponentName component, int callingUid, @UserIdInt int userId) {
        final long ident = mInjector.clearCallingIdentity();
        try {
            final List<ResolveInfo> apps =
                    mInjector.getPackageManagerInternal().queryIntentActivities(
                            launchIntent,
                            MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                            callingUid,
                            userId);
            final int size = apps.size();
            for (int i = 0; i < size; ++i) {
                final ActivityInfo activityInfo = apps.get(i).activityInfo;
                if (TextUtils.equals(activityInfo.packageName, component.getPackageName())
                        && TextUtils.equals(activityInfo.name, component.getClassName())
                        && activityInfo.exported) {
                    return;
                }
            }
            throw new SecurityException("Attempt to launch activity without "
                    + " category Intent.CATEGORY_LAUNCHER or activity is not exported" + component);
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    private boolean isSameProfileGroup(@UserIdInt int callerUserId, @UserIdInt int userId) {
        final long ident = mInjector.clearCallingIdentity();
        try {
            return mInjector.getUserManager().isSameProfileGroup(callerUserId, userId);
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    /**
     * Verify that the given calling package is belong to the calling UID.
     */
    private void verifyCallingPackage(String callingPackage) {
        mInjector.getAppOpsManager().checkPackage(mInjector.getCallingUid(), callingPackage);
    }

    private static class InjectorImpl implements Injector {
        private Context mContext;

        public InjectorImpl(Context context) {
            mContext = context;
        }

        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        public int getCallingUserId() {
            return UserHandle.getCallingUserId();
        }

        public UserHandle getCallingUserHandle() {
            return Binder.getCallingUserHandle();
        }

        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        public void restoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        public UserManager getUserManager() {
            return mContext.getSystemService(UserManager.class);
        }

        public PackageManagerInternal getPackageManagerInternal() {
            return LocalServices.getService(PackageManagerInternal.class);
        }

        public PackageManager getPackageManager() {
            return mContext.getPackageManager();
        }

        public AppOpsManager getAppOpsManager() {
            return mContext.getSystemService(AppOpsManager.class);
        }

        @Override
        public ActivityManagerInternal getActivityManagerInternal() {
            return LocalServices.getService(ActivityManagerInternal.class);
        }

        @Override
        public ActivityTaskManagerInternal getActivityTaskManagerInternal() {
            return LocalServices.getService(ActivityTaskManagerInternal.class);
        }
    }

    @VisibleForTesting
    public interface Injector {
        int getCallingUid();

        int getCallingUserId();

        UserHandle getCallingUserHandle();

        long clearCallingIdentity();

        void restoreCallingIdentity(long token);

        UserManager getUserManager();

        PackageManagerInternal getPackageManagerInternal();

        PackageManager getPackageManager();

        AppOpsManager getAppOpsManager();

        ActivityManagerInternal getActivityManagerInternal();

        ActivityTaskManagerInternal getActivityTaskManagerInternal();
    }
}
