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

import static android.app.AppOpsManager.OP_INTERACT_ACROSS_PROFILES;
import static android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY;
import static android.content.pm.CrossProfileApps.ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

import android.Manifest;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.Mode;
import android.app.IApplicationThread;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.ActivityInfo;
import android.content.pm.CrossProfileAppsInternal;
import android.content.pm.ICrossProfileApps;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.FunctionalUtils.ThrowingSupplier;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CrossProfileAppsServiceImpl extends ICrossProfileApps.Stub {
    private static final String TAG = "CrossProfileAppsService";

    private final LocalService mLocalService = new LocalService();

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
        Objects.requireNonNull(callingPackage);

        verifyCallingPackage(callingPackage);

        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.CROSS_PROFILE_APPS_GET_TARGET_USER_PROFILES)
                .setStrings(new String[] {callingPackage})
                .write();

        return getTargetUserProfilesUnchecked(
                callingPackage, mInjector.getCallingUserId());
    }

    @Override
    public void startActivityAsUser(
            IApplicationThread caller,
            String callingPackage,
            String callingFeatureId,
            ComponentName component,
            @UserIdInt int userId,
            boolean launchMainActivity) throws RemoteException {
        Objects.requireNonNull(callingPackage);
        Objects.requireNonNull(component);

        verifyCallingPackage(callingPackage);

        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.CROSS_PROFILE_APPS_START_ACTIVITY_AS_USER)
                .setStrings(new String[] {callingPackage})
                .write();

        final int callerUserId = mInjector.getCallingUserId();
        final int callingUid = mInjector.getCallingUid();
        final int callingPid = mInjector.getCallingPid();

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
                final int permissionFlag =  PermissionChecker.checkPermissionForPreflight(
                        mContext,
                        android.Manifest.permission.INTERACT_ACROSS_PROFILES,
                        callingPid,
                        callingUid,
                        callingPackage);
                if (permissionFlag != PermissionChecker.PERMISSION_GRANTED
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
                caller, callingPackage, callingFeatureId, launchIntent,
                /* resultTo= */ null,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                launchMainActivity
                        ? ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle()
                        : null,
                userId);
    }

    @Override
    public void startActivityAsUserByIntent(
            IApplicationThread caller,
            String callingPackage,
            String callingFeatureId,
            Intent intent,
            @UserIdInt int userId,
            IBinder callingActivity,
            Bundle options) throws RemoteException {
        Objects.requireNonNull(callingPackage);
        Objects.requireNonNull(intent);
        Objects.requireNonNull(intent.getComponent(), "The intent must have a Component set");

        verifyCallingPackage(callingPackage);

        final int callerUserId = mInjector.getCallingUserId();
        final int callingUid = mInjector.getCallingUid();

        List<UserHandle> allowedTargetUsers = getTargetUserProfilesUnchecked(
                callingPackage, callerUserId);
        if (callerUserId != userId && !allowedTargetUsers.contains(UserHandle.of(userId))) {
            throw new SecurityException(callingPackage + " cannot access unrelated user " + userId);
        }

        Intent launchIntent = new Intent(intent);
        launchIntent.setPackage(callingPackage);

        if (!callingPackage.equals(launchIntent.getComponent().getPackageName())) {
            throw new SecurityException(
                    callingPackage + " attempts to start an activity in other package - "
                            + launchIntent.getComponent().getPackageName());
        }

        if (callerUserId != userId) {
            if (!hasCallerGotInteractAcrossProfilesPermission(callingPackage)) {
                throw new SecurityException("Attempt to launch activity without required "
                        + android.Manifest.permission.INTERACT_ACROSS_PROFILES + " permission"
                        + " or target user is not in the same profile group.");
            }
        }

        verifyActivityCanHandleIntent(launchIntent, callingUid, userId);

        mInjector.getActivityTaskManagerInternal()
                .startActivityAsUser(
                        caller,
                        callingPackage,
                        callingFeatureId,
                        launchIntent,
                        callingActivity,
                        /* startFlags= */ 0,
                        options,
                        userId);
        logStartActivityByIntent(callingPackage);
    }

    private void logStartActivityByIntent(String packageName) {
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.START_ACTIVITY_BY_INTENT)
                .setStrings(packageName)
                .setBoolean(isCallingUserAManagedProfile())
                .write();
    }

    @Override
    public boolean canRequestInteractAcrossProfiles(String callingPackage) {
        Objects.requireNonNull(callingPackage);
        verifyCallingPackage(callingPackage);
        return canRequestInteractAcrossProfilesUnchecked(callingPackage);
    }

    private boolean canRequestInteractAcrossProfilesUnchecked(String packageName) {
        List<UserHandle> targetUserProfiles =
                getTargetUserProfilesUnchecked(packageName, mInjector.getCallingUserId());
        if (targetUserProfiles.isEmpty()) {
            return false;
        }
        if (!hasRequestedAppOpPermission(
                AppOpsManager.opToPermission(OP_INTERACT_ACROSS_PROFILES), packageName)) {
            return false;
        }
        return isCrossProfilePackageWhitelisted(packageName);
    }

    private boolean hasRequestedAppOpPermission(String permission, String packageName) {
        try {
            String[] packages =
                    mInjector.getIPackageManager().getAppOpPermissionPackages(permission);
            return ArrayUtils.contains(packages, packageName);
        } catch (RemoteException exc) {
            Slog.e(TAG, "PackageManager dead. Cannot get permission info");
            return false;
        }
    }

    @Override
    public boolean canInteractAcrossProfiles(String callingPackage) {
        Objects.requireNonNull(callingPackage);
        verifyCallingPackage(callingPackage);

        final List<UserHandle> targetUserProfiles = getTargetUserProfilesUnchecked(
                callingPackage, mInjector.getCallingUserId());
        if (targetUserProfiles.isEmpty()) {
            return false;
        }
        return hasCallerGotInteractAcrossProfilesPermission(callingPackage);
    }

    private boolean hasCallerGotInteractAcrossProfilesPermission(String callingPackage) {
        return hasInteractAcrossProfilesPermission(
                callingPackage, mInjector.getCallingUid(), mInjector.getCallingPid());
    }

    private boolean isCrossProfilePackageWhitelisted(String packageName) {
        return mInjector.withCleanCallingIdentity(() ->
                mInjector.getDevicePolicyManagerInternal()
                        .getAllCrossProfilePackages().contains(packageName));
    }

    private List<UserHandle> getTargetUserProfilesUnchecked(
            String packageName, @UserIdInt int userId) {
        return mInjector.withCleanCallingIdentity(() -> {
            final int[] enabledProfileIds =
                    mInjector.getUserManager().getEnabledProfileIds(userId);

            List<UserHandle> targetProfiles = new ArrayList<>();
            for (final int profileId : enabledProfileIds) {
                if (profileId == userId) {
                    continue;
                }
                if (!isPackageEnabled(packageName, profileId)) {
                    continue;
                }
                targetProfiles.add(UserHandle.of(profileId));
            }
            return targetProfiles;
        });
    }

    private boolean isPackageEnabled(String packageName, @UserIdInt int userId) {
        final int callingUid = mInjector.getCallingUid();
        return mInjector.withCleanCallingIdentity(() -> {
            final PackageInfo info = mInjector.getPackageManagerInternal()
                    .getPackageInfo(
                            packageName,
                            MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                            callingUid,
                            userId);
            return info != null && info.applicationInfo.enabled;
        });
    }

    private void verifyActivityCanHandleIntent(
            Intent launchIntent, int callingUid, @UserIdInt int userId) {
        mInjector.withCleanCallingIdentity(() -> {
            final List<ResolveInfo> activities =
                    mInjector.getPackageManagerInternal().queryIntentActivities(
                            launchIntent,
                            launchIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                            MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                            callingUid,
                            userId);

            if (!activities.isEmpty()) {
                return;
            }
            throw new SecurityException("Activity cannot handle intent");
        });
    }

    /**
     * Verify that the specified intent does resolved to the specified component and the resolved
     * activity is exported.
     */
    private void verifyActivityCanHandleIntentAndExported(
            Intent launchIntent, ComponentName component, int callingUid, @UserIdInt int userId) {
        mInjector.withCleanCallingIdentity(() -> {
            final List<ResolveInfo> apps =
                    mInjector.getPackageManagerInternal().queryIntentActivities(
                            launchIntent,
                            launchIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
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
        });
    }

    /**
     * See {@link android.content.pm.CrossProfileApps#setInteractAcrossProfilesAppOp(String, int)}.
     *
     * <p>Logs metrics. Use {@link #setInteractAcrossProfilesAppOpUnchecked(String, int, boolean)}
     * to avoid permission checks or to specify not to log metrics.
     */
    @Override
    public void setInteractAcrossProfilesAppOp(String packageName, @Mode int newMode) {
        final int callingUid = mInjector.getCallingUid();
        if (!isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS_FULL, callingUid)
                && !isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS, callingUid)) {
            throw new SecurityException(
                    "INTERACT_ACROSS_USERS or INTERACT_ACROSS_USERS_FULL is required to set the"
                            + " app-op for interacting across profiles.");
        }
        if (!isPermissionGranted(Manifest.permission.MANAGE_APP_OPS_MODES, callingUid)
                && !isPermissionGranted(
                        Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES, callingUid)) {
            throw new SecurityException(
                    "MANAGE_APP_OPS_MODES or CONFIGURE_INTERACT_ACROSS_PROFILES is required to set"
                            + " the app-op for interacting across profiles.");
        }
        setInteractAcrossProfilesAppOpUnchecked(packageName, newMode, /* logMetrics= */ true);
    }

    private void setInteractAcrossProfilesAppOpUnchecked(
            String packageName, @Mode int newMode, boolean logMetrics) {
        if (newMode == AppOpsManager.MODE_ALLOWED
                && !canConfigureInteractAcrossProfiles(packageName)) {
            // The user should not be prompted for apps that cannot request to interact across
            // profiles. However, we return early here if required to avoid race conditions.
            Slog.e(TAG, "Tried to turn on the appop for interacting across profiles for invalid"
                    + " app " + packageName);
            return;
        }
        final int[] profileIds =
                mInjector.getUserManager()
                        .getProfileIds(mInjector.getCallingUserId(), /* enabledOnly= */ false);
        for (int profileId : profileIds) {
            if (!isPackageInstalled(packageName, profileId)) {
                continue;
            }
            setInteractAcrossProfilesAppOpForUser(packageName, newMode, profileId, logMetrics);
        }
    }

    private boolean isPackageInstalled(String packageName, @UserIdInt int userId) {
        final int callingUid = mInjector.getCallingUid();
        return mInjector.withCleanCallingIdentity(() -> {
            final PackageInfo info =
                    mInjector.getPackageManagerInternal()
                            .getPackageInfo(
                                    packageName,
                                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                                    callingUid,
                                    userId);
            return info != null;
        });
    }

    private void setInteractAcrossProfilesAppOpForUser(
            String packageName, @Mode int newMode, @UserIdInt int userId, boolean logMetrics) {
        try {
            setInteractAcrossProfilesAppOpForUserOrThrow(packageName, newMode, userId, logMetrics);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Missing package " + packageName + " on user ID " + userId, e);
        }
    }

    private void setInteractAcrossProfilesAppOpForUserOrThrow(
            String packageName, @Mode int newMode, @UserIdInt int userId, boolean logMetrics)
            throws PackageManager.NameNotFoundException {
        final int uid = mInjector.getPackageManager()
                .getPackageUidAsUser(packageName, /* flags= */ 0, userId);
        if (currentModeEquals(newMode, packageName, uid)) {
            Slog.w(TAG, "Attempt to set mode to existing value of " + newMode + " for "
                    + packageName + " on user ID " + userId);
            return;
        }
        final int callingUid = mInjector.getCallingUid();
        if (isPermissionGranted(
                Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES, callingUid)) {
            // Clear calling identity since the CONFIGURE_INTERACT_ACROSS_PROFILES permission allows
            // this particular app-op to be modified without the broader app-op permissions.
            mInjector.withCleanCallingIdentity(() ->
                    mInjector.getAppOpsManager()
                            .setMode(OP_INTERACT_ACROSS_PROFILES, uid, packageName, newMode));
        } else {
            mInjector.getAppOpsManager()
                    .setMode(OP_INTERACT_ACROSS_PROFILES, uid, packageName, newMode);
        }
        sendCanInteractAcrossProfilesChangedBroadcast(packageName, uid, UserHandle.of(userId));
        maybeLogSetInteractAcrossProfilesAppOp(packageName, newMode, userId, logMetrics, uid);
    }

    private void maybeLogSetInteractAcrossProfilesAppOp(
            String packageName,
            @Mode int newMode,
            @UserIdInt int userId,
            boolean logMetrics,
            int uid) {
        if (!logMetrics) {
            return;
        }
        if (userId != mInjector.getCallingUserId()) {
            // Only log once per profile group by checking for the calling user ID.
            return;
        }
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.SET_INTERACT_ACROSS_PROFILES_APP_OP)
                .setStrings(packageName)
                .setInt(newMode)
                .setBoolean(appDeclaresCrossProfileAttribute(uid))
                .write();
    }

    /**
     * Returns whether the given app-op mode is equivalent to the currently-set app-op of the given
     * package name and UID. Clears identity to avoid permission checks, so ensure the caller does
     * any necessary permission checks.
     */
    private boolean currentModeEquals(@Mode int otherMode, String packageName, int uid) {
        final String op =
                AppOpsManager.permissionToOp(Manifest.permission.INTERACT_ACROSS_PROFILES);
        return mInjector.withCleanCallingIdentity(() -> otherMode
                == mInjector.getAppOpsManager().unsafeCheckOpNoThrow(op, uid, packageName));
    }

    private void sendCanInteractAcrossProfilesChangedBroadcast(
            String packageName, int uid, UserHandle userHandle) {
        final Intent intent =
                new Intent(ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED).setPackage(packageName);
        if (appDeclaresCrossProfileAttribute(uid)) {
            intent.addFlags(
                    Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND | Intent.FLAG_RECEIVER_FOREGROUND);
        } else {
            intent.addFlags(FLAG_RECEIVER_REGISTERED_ONLY);
        }
        for (ResolveInfo receiver : findBroadcastReceiversForUser(intent, userHandle)) {
            intent.setComponent(receiver.getComponentInfo().getComponentName());
            mInjector.sendBroadcastAsUser(intent, userHandle);
        }
    }

    private List<ResolveInfo> findBroadcastReceiversForUser(Intent intent, UserHandle userHandle) {
        return mInjector.getPackageManager()
                .queryBroadcastReceiversAsUser(intent, /* flags= */ 0, userHandle);
    }

    private boolean appDeclaresCrossProfileAttribute(int uid) {
        return mInjector.getPackageManagerInternal().getPackage(uid).isCrossProfile();
    }

    @Override
    public boolean canConfigureInteractAcrossProfiles(String packageName) {
        if (!hasOtherProfileWithPackageInstalled(packageName, mInjector.getCallingUserId())) {
            return false;
        }
        if (!hasRequestedAppOpPermission(
                AppOpsManager.opToPermission(OP_INTERACT_ACROSS_PROFILES), packageName)) {
            return false;
        }
        return isCrossProfilePackageWhitelisted(packageName);
    }

    private boolean hasOtherProfileWithPackageInstalled(String packageName, @UserIdInt int userId) {
        return mInjector.withCleanCallingIdentity(() -> {
            final int[] profileIds =
                    mInjector.getUserManager().getProfileIds(userId, /* enabledOnly= */ false);
            for (int profileId : profileIds) {
                if (profileId != userId && isPackageInstalled(packageName, profileId)) {
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public void resetInteractAcrossProfilesAppOps(List<String> packageNames) {
        packageNames.forEach(this::resetInteractAcrossProfilesAppOp);
    }

    private void resetInteractAcrossProfilesAppOp(String packageName) {
        if (canConfigureInteractAcrossProfiles(packageName)) {
            Slog.w(TAG, "Not resetting app-op for package " + packageName
                    + " since it is still configurable by users.");
            return;
        }
        final String op =
                AppOpsManager.permissionToOp(Manifest.permission.INTERACT_ACROSS_PROFILES);
        setInteractAcrossProfilesAppOp(packageName, AppOpsManager.opToDefaultMode(op));
    }

    CrossProfileAppsInternal getLocalService() {
        return mLocalService;
    }

    private boolean isSameProfileGroup(@UserIdInt int callerUserId, @UserIdInt int userId) {
        return mInjector.withCleanCallingIdentity(() ->
                mInjector.getUserManager().isSameProfileGroup(callerUserId, userId));
    }

    /**
     * Verify that the given calling package is belong to the calling UID.
     */
    private void verifyCallingPackage(String callingPackage) {
        mInjector.getAppOpsManager().checkPackage(mInjector.getCallingUid(), callingPackage);
    }

    private boolean isPermissionGranted(String permission, int uid) {
        return PackageManager.PERMISSION_GRANTED == mInjector.checkComponentPermission(
                permission, uid, /* owningUid= */-1, /* exported= */ true);
    }

    private boolean isCallingUserAManagedProfile() {
        return isManagedProfile(mInjector.getCallingUserId());
    }

    private boolean isManagedProfile(@UserIdInt int userId) {
        return mInjector.withCleanCallingIdentity(()
                -> mContext.getSystemService(UserManager.class).isManagedProfile(userId));
    }

    private boolean hasInteractAcrossProfilesPermission(String packageName, int uid, int pid) {
        if (isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid)
                || isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS, uid)) {
            return true;
        }
        return PermissionChecker.PERMISSION_GRANTED
                == PermissionChecker.checkPermissionForPreflight(
                        mContext,
                        Manifest.permission.INTERACT_ACROSS_PROFILES,
                        pid,
                        uid,
                        packageName);
    }

    private static class InjectorImpl implements Injector {
        private Context mContext;

        public InjectorImpl(Context context) {
            mContext = context;
        }

        @Override
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        @Override
        public int getCallingPid() {
            return Binder.getCallingPid();
        }

        @Override
        public int getCallingUserId() {
            return UserHandle.getCallingUserId();
        }

        @Override
        public UserHandle getCallingUserHandle() {
            return Binder.getCallingUserHandle();
        }

        @Override
        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        @Override
        public void restoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        @Override
        public void withCleanCallingIdentity(ThrowingRunnable action) {
            Binder.withCleanCallingIdentity(action);
        }

        @Override
        public final <T> T withCleanCallingIdentity(ThrowingSupplier<T> action) {
            return Binder.withCleanCallingIdentity(action);
        }

        @Override
        public UserManager getUserManager() {
            return mContext.getSystemService(UserManager.class);
        }

        @Override
        public PackageManagerInternal getPackageManagerInternal() {
            return LocalServices.getService(PackageManagerInternal.class);
        }

        @Override
        public PackageManager getPackageManager() {
            return mContext.getPackageManager();
        }

        @Override
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

        @Override
        public IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }

        @Override
        public DevicePolicyManagerInternal getDevicePolicyManagerInternal() {
            return LocalServices.getService(DevicePolicyManagerInternal.class);
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            mContext.sendBroadcastAsUser(intent, user);
        }

        @Override
        public int checkComponentPermission(
                String permission, int uid, int owningUid, boolean exported) {
            return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
        }
    }

    @VisibleForTesting
    public interface Injector {
        int getCallingUid();

        int getCallingPid();

        int getCallingUserId();

        UserHandle getCallingUserHandle();

        long clearCallingIdentity();

        void restoreCallingIdentity(long token);

        void withCleanCallingIdentity(ThrowingRunnable action);

        <T> T withCleanCallingIdentity(ThrowingSupplier<T> action);

        UserManager getUserManager();

        PackageManagerInternal getPackageManagerInternal();

        PackageManager getPackageManager();

        AppOpsManager getAppOpsManager();

        ActivityManagerInternal getActivityManagerInternal();

        ActivityTaskManagerInternal getActivityTaskManagerInternal();

        IPackageManager getIPackageManager();

        DevicePolicyManagerInternal getDevicePolicyManagerInternal();

        void sendBroadcastAsUser(Intent intent, UserHandle user);

        int checkComponentPermission(String permission, int uid, int owningUid, boolean exported);
    }

    class LocalService extends CrossProfileAppsInternal {

        @Override
        public boolean verifyPackageHasInteractAcrossProfilePermission(
                String packageName, @UserIdInt int userId)
                throws PackageManager.NameNotFoundException {
            final int uid = Objects.requireNonNull(
                    mInjector.getPackageManager().getApplicationInfoAsUser(
                            Objects.requireNonNull(packageName), /* flags= */ 0, userId)).uid;
            return verifyUidHasInteractAcrossProfilePermission(packageName, uid);
        }

        @Override
        public boolean verifyUidHasInteractAcrossProfilePermission(String packageName, int uid) {
            Objects.requireNonNull(packageName);
            return hasInteractAcrossProfilesPermission(
                    packageName, uid, PermissionChecker.PID_UNKNOWN);
        }

        @Override
        public List<UserHandle> getTargetUserProfiles(String packageName, int userId) {
            return getTargetUserProfilesUnchecked(packageName, userId);
        }
    }
}
