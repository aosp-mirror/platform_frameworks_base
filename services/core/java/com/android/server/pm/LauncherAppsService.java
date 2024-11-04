/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.app.ActivityOptions.KEY_SPLASH_SCREEN_THEME;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_ARCHIVE_ICON_OVERLAY;
import static android.app.AppOpsManager.OP_UNARCHIVAL_CONFIRMATION;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_MUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.content.PermissionChecker.PERMISSION_GRANTED;
import static android.content.PermissionChecker.checkCallingOrSelfPermissionForPreflight;
import static android.content.pm.LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS;
import static android.content.pm.LauncherApps.FLAG_CACHE_NOTIFICATION_SHORTCUTS;
import static android.content.pm.LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS;

import static com.android.server.pm.PackageArchiver.isArchivingEnabled;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ILauncherApps;
import android.content.pm.IOnAppsChangedListener;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IPackageManager;
import android.content.pm.IShortcutChangeCallback;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.LauncherActivityInfoInternal;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.LauncherUserInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutQueryWrapper;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.graphics.Rect;
import android.multiuser.Flags;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.window.IDumpCallback;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.SizedInputStream;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service that manages requests and callbacks for launchers that support
 * managed profiles.
 */
public class LauncherAppsService extends SystemService {
    private static final String WM_TRACE_DIR = "/data/misc/wmtrace/";
    private static final String VC_FILE_SUFFIX = ".vc";
    private static final String PS_SETTINGS_INTENT =
            "com.android.settings.action.OPEN_PRIVATE_SPACE_SETTINGS";

    private static final Set<PosixFilePermission> WM_TRACE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OWNER_READ
    );

    private final LauncherAppsImpl mLauncherAppsImpl;

    public LauncherAppsService(Context context) {
        super(context);
        mLauncherAppsImpl = new LauncherAppsImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.LAUNCHER_APPS_SERVICE, mLauncherAppsImpl);
        mLauncherAppsImpl.registerLoadingProgressForIncrementalApps();
        LocalServices.addService(LauncherAppsServiceInternal.class, mLauncherAppsImpl.mInternal);
    }

    static class BroadcastCookie {
        public final UserHandle user;
        public final String packageName;
        public final int callingUid;
        public final int callingPid;

        BroadcastCookie(UserHandle userHandle, String packageName, int callingPid, int callingUid) {
            this.user = userHandle;
            this.packageName = packageName;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
        }
    }

    /**
     * Local system service interface.
     * @hide Only for use within system server
     */
    public abstract static class LauncherAppsServiceInternal {
        /** Same as startShortcut except supports forwarding of caller uid/pid. */
        public abstract boolean startShortcut(int callerUid, int callerPid, String callingPackage,
                String packageName, String featureId, String shortcutId, Rect sourceBounds,
                Bundle startActivityOptions, int targetUserId);
    }

    @VisibleForTesting
    static class LauncherAppsImpl extends ILauncherApps.Stub {
        private static final boolean DEBUG = false;
        private static final String TAG = "LauncherAppsService";
        private static final String NAMESPACE_MULTIUSER = "multiuser";
        private static final String FLAG_NON_SYSTEM_ACCESS_TO_HIDDEN_PROFILES =
                "allow_3p_launchers_access_via_launcher_apps_apis";
        private final Context mContext;
        private final UserManager mUm;
        private final RoleManager mRoleManager;
        private final IPackageManager mIPM;
        private final UserManagerInternal mUserManagerInternal;
        private final UsageStatsManagerInternal mUsageStatsManagerInternal;
        private final ActivityManagerInternal mActivityManagerInternal;
        private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
        private final ShortcutServiceInternal mShortcutServiceInternal;
        private final PackageManagerInternal mPackageManagerInternal;
        private final AppOpsManager mAppOpsManager;
        private final PackageCallbackList<IOnAppsChangedListener> mListeners
                = new PackageCallbackList<IOnAppsChangedListener>();
        private final DevicePolicyManager mDpm;

        private final PackageRemovedListener mPackageRemovedListener =
                new PackageRemovedListener();
        private final MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

        @GuardedBy("mListeners")
        private boolean mIsWatchingPackageBroadcasts = false;

        private final ShortcutChangeHandler mShortcutChangeHandler;

        private final Handler mCallbackHandler;
        private final ExecutorService mOnDumpExecutor = Executors.newSingleThreadExecutor();

        private PackageInstallerService mPackageInstallerService;

        final LauncherAppsServiceInternal mInternal;

        @NonNull
        private final RemoteCallbackList<IDumpCallback> mDumpCallbacks =
                new RemoteCallbackList<>();

        public LauncherAppsImpl(Context context) {
            mContext = context;
            mIPM = AppGlobals.getPackageManager();
            mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            mRoleManager = mContext.getSystemService(RoleManager.class);
            mUserManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(UserManagerInternal.class));
            mUsageStatsManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(UsageStatsManagerInternal.class));
            mActivityManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(ActivityManagerInternal.class));
            mActivityTaskManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(ActivityTaskManagerInternal.class));
            mShortcutServiceInternal = Objects.requireNonNull(
                    LocalServices.getService(ShortcutServiceInternal.class));
            mPackageManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(PackageManagerInternal.class));
            mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
            mShortcutServiceInternal.addListener(mPackageMonitor);
            mShortcutChangeHandler = new ShortcutChangeHandler(mUserManagerInternal);
            mShortcutServiceInternal.addShortcutChangeCallback(mShortcutChangeHandler);
            mCallbackHandler = BackgroundThread.getHandler();
            mDpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            mInternal = new LocalService();
        }

        @VisibleForTesting
        int injectBinderCallingUid() {
            return getCallingUid();
        }

        @VisibleForTesting
        int injectBinderCallingPid() {
            return getCallingPid();
        }

        final int injectCallingUserId() {
            return UserHandle.getUserId(injectBinderCallingUid());
        }

        @VisibleForTesting
        long injectClearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        // Injection point.
        @VisibleForTesting
        void injectRestoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        private int getCallingUserId() {
            return UserHandle.getUserId(injectBinderCallingUid());
        }

        /*
         * @see android.content.pm.ILauncherApps#addOnAppsChangedListener
         */
        @Override
        public void addOnAppsChangedListener(String callingPackage, IOnAppsChangedListener listener)
                throws RemoteException {
            verifyCallingPackage(callingPackage);
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Adding listener from " + Binder.getCallingUserHandle());
                }
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    if (DEBUG) {
                        Log.d(TAG, "Starting package monitoring");
                    }
                    startWatchingPackageBroadcasts();
                }
                mListeners.unregister(listener);
                mListeners.register(listener, new BroadcastCookie(UserHandle.of(getCallingUserId()),
                        callingPackage, injectBinderCallingPid(), injectBinderCallingUid()));
            }
        }

        /*
         * @see android.content.pm.ILauncherApps#removeOnAppsChangedListener
         */
        @Override
        public void removeOnAppsChangedListener(IOnAppsChangedListener listener)
                throws RemoteException {
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Removing listener from " + Binder.getCallingUserHandle());
                }
                mListeners.unregister(listener);
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    stopWatchingPackageBroadcasts();
                }
            }
        }

        /**
         * @see android.content.pm.ILauncherApps#registerPackageInstallerCallback
         */
        @Override
        public void registerPackageInstallerCallback(String callingPackage,
                IPackageInstallerCallback callback) {
            verifyCallingPackage(callingPackage);
            BroadcastCookie callerInfo =
                    new BroadcastCookie(
                            new UserHandle(getCallingUserId()),
                            callingPackage,
                            getCallingPid(),
                            getCallingUid());
            getPackageInstallerService()
                    .registerCallback(
                            callback,
                            eventUserId ->
                                    isEnabledProfileOf(
                                            callerInfo,
                                            new UserHandle(eventUserId),
                                            "shouldReceiveEvent"));
        }

        @Override
        public List<UserHandle> getUserProfiles() {
            int[] userIds;
            if (!canAccessHiddenProfile(getCallingUid(), getCallingPid())) {
                userIds = mUm.getProfileIdsExcludingHidden(getCallingUserId(), /* enabled= */ true);
            } else {
                userIds = mUm.getEnabledProfileIds(getCallingUserId());
            }
            final List<UserHandle> result = new ArrayList<>(userIds.length);
            for (int userId : userIds) {
                result.add(UserHandle.of(userId));
            }
            return result;
        }

        @Override
        public ParceledListSlice<SessionInfo> getAllSessions(String callingPackage) {
            verifyCallingPackage(callingPackage);
            List<SessionInfo> sessionInfos = new ArrayList<>();
            final int callingUid = Binder.getCallingUid();

            int[] userIds;
            if (!canAccessHiddenProfile(callingUid, Binder.getCallingPid())) {
                userIds = mUm.getProfileIdsExcludingHidden(getCallingUserId(), /* enabled= */ true);
            } else {
                userIds = mUm.getEnabledProfileIds(getCallingUserId());
            }

            final long token = Binder.clearCallingIdentity();
            try {
                for (int userId : userIds) {
                    sessionInfos.addAll(getPackageInstallerService().getAllSessions(userId)
                            .getList());
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            sessionInfos.removeIf(info -> shouldFilterSession(callingUid, info));
            return new ParceledListSlice<>(sessionInfos);
        }

        private boolean shouldFilterSession(int uid, SessionInfo session) {
            if (session == null) {
                return false;
            }
            return uid != session.getInstallerUid()
                    && !mPackageManagerInternal.canQueryPackage(uid, session.getAppPackageName());
        }

        private PackageInstallerService getPackageInstallerService() {
            if (mPackageInstallerService == null) {
                try {
                    mPackageInstallerService = ((PackageInstallerService) ((IPackageManager)
                            ServiceManager.getService("package")).getPackageInstaller());
                } catch (RemoteException e) {
                    Slog.wtf(TAG, "Error getting IPackageInstaller", e);
                }
            }
            return mPackageInstallerService;
        }

        /**
         * Register a receiver to watch for package broadcasts
         */
        private void startWatchingPackageBroadcasts() {
            if (!mIsWatchingPackageBroadcasts) {
                final IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED_INTERNAL);
                filter.addDataScheme("package");
                mContext.registerReceiverAsUser(mPackageRemovedListener, UserHandle.ALL, filter,
                        /* broadcastPermission= */ null, mCallbackHandler);
                final long identity = Binder.clearCallingIdentity();
                try {
                    mPackageMonitor.register(mContext, UserHandle.ALL, mCallbackHandler);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                mIsWatchingPackageBroadcasts = true;
            }
        }

        /**
         * Unregister package broadcast receiver
         */
        private void stopWatchingPackageBroadcasts() {
            if (DEBUG) {
                Log.d(TAG, "Stopped watching for packages");
            }
            if (mIsWatchingPackageBroadcasts) {
                mContext.unregisterReceiver(mPackageRemovedListener);
                mPackageMonitor.unregister();
                mIsWatchingPackageBroadcasts = false;
            }
        }

        void checkCallbackCount() {
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Callback count = " + mListeners.getRegisteredCallbackCount());
                }
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    stopWatchingPackageBroadcasts();
                }
            }
        }

        /**
         * Checks if the calling user is in the same group as {@code targetUser}, and allowed
         * to access it.
         *
         * @return TRUE if the calling user can access {@code targetUserId}.  FALSE if not *but
         * they're still in the same profile group*.
         *
         * @throws SecurityException if the calling user and {@code targetUser} are not in the same
         * group.
         */
        private boolean canAccessProfile(int targetUserId, String message) {
            return canAccessProfile(injectBinderCallingUid(), injectCallingUserId(),
                    injectBinderCallingPid(), targetUserId, message);
        }

        private boolean canAccessProfile(int callingUid, int callingUserId, int callingPid,
                int targetUserId, String message) {
            if (targetUserId == callingUserId) return true;
            if (injectHasInteractAcrossUsersFullPermission(callingPid, callingUid)) {
                return true;
            }

            long ident = injectClearCallingIdentity();
            try {
                final UserInfo callingUserInfo = mUm.getUserInfo(callingUserId);
                if (callingUserInfo != null && callingUserInfo.isProfile()) {
                    Slog.w(TAG, message + " for another profile "
                            + targetUserId + " from " + callingUserId + " not allowed");
                    return false;
                }
            } finally {
                injectRestoreCallingIdentity(ident);
            }

            if (isHiddenProfile(UserHandle.of(targetUserId))
                    && !canAccessHiddenProfile(callingUid, callingPid)) {
                return false;
            }

            return mUserManagerInternal.isProfileAccessible(callingUserId, targetUserId,
                    message, true);
        }

        private boolean isHiddenProfile(UserHandle targetUser) {
            if (!Flags.enableLauncherAppsHiddenProfileChecks()) {
                return false;
            }

            long identity = injectClearCallingIdentity();
            try {
                UserProperties properties = mUm.getUserProperties(targetUser);
                if (properties == null) {
                    return false;
                }

                return properties.getProfileApiVisibility()
                        == UserProperties.PROFILE_API_VISIBILITY_HIDDEN;
            } catch (IllegalArgumentException e) {
                return false;
            } finally {
                injectRestoreCallingIdentity(identity);
            }
        }

        private void verifyCallingPackage(String callingPackage) {
            verifyCallingPackage(callingPackage, injectBinderCallingUid());
        }

        private boolean canAccessHiddenProfile(int callingUid, int callingPid) {
            if (!areHiddenApisChecksEnabled()) {
                return true;
            }

            long ident = injectClearCallingIdentity();
            try {
                AndroidPackage callingPackage = mPackageManagerInternal.getPackage(callingUid);
                if (callingPackage == null) {
                    return false;
                }

                if (mContext.checkPermission(
                                Manifest.permission.ACCESS_HIDDEN_PROFILES_FULL,
                                callingPid,
                                callingUid)
                        == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }

                if (isAccessToHiddenProfilesForNonSystemAppsForbidden()) {
                    return false;
                }

                if (!mRoleManager
                        .getRoleHoldersAsUser(
                                RoleManager.ROLE_HOME, UserHandle.getUserHandleForUid(callingUid))
                        .contains(callingPackage.getPackageName())) {
                    return false;
                }

                return mContext.checkPermission(
                                android.Manifest.permission.ACCESS_HIDDEN_PROFILES,
                                callingPid,
                                callingUid)
                        == PackageManager.PERMISSION_GRANTED;
            } finally {
                injectRestoreCallingIdentity(ident);
            }
        }

        private boolean isAccessToHiddenProfilesForNonSystemAppsForbidden() {
            return !DeviceConfig.getBoolean(
                    NAMESPACE_MULTIUSER,
                    FLAG_NON_SYSTEM_ACCESS_TO_HIDDEN_PROFILES,
                    /* defaultValue= */ true);
        }

        private boolean areHiddenApisChecksEnabled() {
            return android.os.Flags.allowPrivateProfile()
                    && Flags.enableHidingProfiles()
                    && Flags.enableLauncherAppsHiddenProfileChecks()
                    && Flags.enablePermissionToAccessHiddenProfiles()
                    && Flags.enablePrivateSpaceFeatures();
        }

        @VisibleForTesting // We override it in unit tests
        void verifyCallingPackage(String callingPackage, int callerUid) {
            int packageUid = -1;
            try {
                packageUid = mIPM.getPackageUid(callingPackage,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                | PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        UserHandle.getUserId(callerUid));
            } catch (RemoteException ignore) {
            }
            if (packageUid < 0) {
                Log.e(TAG, "Package not found: " + callingPackage);
            }
            if (packageUid != callerUid) {
                throw new SecurityException("Calling package name mismatch");
            }
        }

        private LauncherActivityInfoInternal getHiddenAppActivityInfo(String packageName,
                int callingUid, UserHandle user) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(packageName,
                    PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME));
            final List<LauncherActivityInfoInternal> apps = queryIntentLauncherActivities(intent,
                    callingUid, user);
            if (apps.size() > 0) {
                return apps.get(0);
            }
            return null;
        }

        @Override
        public boolean shouldHideFromSuggestions(String packageName, UserHandle user) {
            final int userId = user.getIdentifier();
            if (!canAccessProfile(userId, "cannot get shouldHideFromSuggestions")) {
                return false;
            }
            if (isArchivingEnabled() && packageName != null
                    && isPackageArchived(packageName, user)) {
                return true;
            }
            if (mPackageManagerInternal.filterAppAccess(
                    packageName, Binder.getCallingUid(), userId)) {
                return false;
            }
            final int flags = mPackageManagerInternal.getDistractingPackageRestrictions(
                    packageName, userId);
            return (flags & PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS) != 0;
        }

        @Override
        public ParceledListSlice<LauncherActivityInfoInternal> getLauncherActivities(
                String callingPackage, @Nullable String packageName, UserHandle user)
                throws RemoteException {
            ParceledListSlice<LauncherActivityInfoInternal> launcherActivities =
                    queryActivitiesForUser(
                            callingPackage,
                            new Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setPackage(packageName),
                            user);
            if (isArchivingEnabled()) {
                launcherActivities =
                        getActivitiesForArchivedApp(packageName, user, launcherActivities);
            }
            if (Settings.Global.getInt(
                            mContext.getContentResolver(),
                            Settings.Global.SHOW_HIDDEN_LAUNCHER_ICON_APPS_ENABLED,
                            1)
                    == 0) {
                return launcherActivities;
            }
            if (launcherActivities == null) {
                // Cannot access profile, so we don't even return any hidden apps.
                return null;
            }

            final int callingUid = injectBinderCallingUid();
            final long ident = injectClearCallingIdentity();
            try {
                if (mUm.getUserInfo(user.getIdentifier()).isManagedProfile()) {
                    // Managed profile should not show hidden apps
                    return launcherActivities;
                }
                if (mDpm.getDeviceOwnerComponentOnAnyUser() != null) {
                    // Device owner devices should not show hidden apps
                    return launcherActivities;
                }

                final ArrayList<LauncherActivityInfoInternal> result = new ArrayList<>(
                        launcherActivities.getList());
                if (packageName != null) {
                    // If this hidden app should not be shown, return the original list.
                    // Otherwise, inject hidden activity that forwards user to app details page.
                    if (result.size() > 0) {
                        return launcherActivities;
                    }
                    final ApplicationInfo appInfo = mPackageManagerInternal.getApplicationInfo(
                            packageName, /* flags= */ 0, callingUid, user.getIdentifier());
                    if (shouldShowSyntheticActivity(user, appInfo)) {
                        LauncherActivityInfoInternal info = getHiddenAppActivityInfo(packageName,
                                callingUid, user);
                        if (info != null) {
                            result.add(info);
                        }
                    }
                    return new ParceledListSlice<>(result);
                }
                final HashSet<String> visiblePackages = new HashSet<>();
                for (LauncherActivityInfoInternal info : result) {
                    visiblePackages.add(info.getActivityInfo().packageName);
                }
                final List<ApplicationInfo> installedPackages =
                        mPackageManagerInternal.getInstalledApplications(
                                /* flags= */ 0, user.getIdentifier(), callingUid);
                for (ApplicationInfo applicationInfo : installedPackages) {
                    if (!visiblePackages.contains(applicationInfo.packageName)) {
                        if (!shouldShowSyntheticActivity(user, applicationInfo)) {
                            continue;
                        }
                        LauncherActivityInfoInternal info =
                                getHiddenAppActivityInfo(
                                        applicationInfo.packageName, callingUid, user);
                        if (info != null) {
                            result.add(info);
                        }
                    }
                }
                return new ParceledListSlice<>(result);
            } finally {
                injectRestoreCallingIdentity(ident);
            }
        }

        private ParceledListSlice<LauncherActivityInfoInternal> getActivitiesForArchivedApp(
                @Nullable String packageName,
                UserHandle user,
                ParceledListSlice<LauncherActivityInfoInternal> launcherActivities) {
            final List<LauncherActivityInfoInternal> archivedActivities =
                    generateLauncherActivitiesForArchivedApp(packageName, user);
            if (archivedActivities.isEmpty()) {
                return launcherActivities;
            }
            if (launcherActivities == null) {
                return new ParceledListSlice(archivedActivities);
            }
            List<LauncherActivityInfoInternal> result = launcherActivities.getList();
            result.addAll(archivedActivities);
            return new ParceledListSlice(result);
        }

        private boolean shouldShowSyntheticActivity(UserHandle user, ApplicationInfo appInfo) {
            if (appInfo == null || appInfo.isSystemApp() || appInfo.isUpdatedSystemApp()) {
                return false;
            }
            if (isManagedProfileAdmin(user, appInfo.packageName)) {
                return false;
            }
            final AndroidPackage pkg = mPackageManagerInternal.getPackage(appInfo.packageName);
            if (pkg == null) {
                // Should not happen, but we shouldn't be failing if it does
                return false;
            }
            // If app does not have any default enabled launcher activity or any permissions,
            // the app can legitimately have no icon so we do not show the synthetic activity.
            return requestsPermissions(pkg) && hasDefaultEnableLauncherActivity(
                    appInfo.packageName);
        }

        private boolean requestsPermissions(@NonNull AndroidPackage pkg) {
            return !ArrayUtils.isEmpty(pkg.getRequestedPermissions());
        }

        private boolean hasDefaultEnableLauncherActivity(@NonNull String packageName) {
            final Intent matchIntent = new Intent(Intent.ACTION_MAIN);
            matchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            matchIntent.setPackage(packageName);
            final List<ResolveInfo> infoList = mPackageManagerInternal.queryIntentActivities(
                    matchIntent, matchIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    PackageManager.MATCH_DISABLED_COMPONENTS, Binder.getCallingUid(),
                    getCallingUserId());
            final int size = infoList.size();
            for (int i = 0; i < size; i++) {
                if (infoList.get(i).activityInfo.enabled) {
                    return true;
                }
            }
            return false;
        }

        private boolean isManagedProfileAdmin(UserHandle user, String packageName) {
            final List<UserInfo> userInfoList = mUm.getProfiles(user.getIdentifier());
            for (int i = 0; i < userInfoList.size(); i++) {
                UserInfo userInfo = userInfoList.get(i);
                if (!userInfo.isManagedProfile()) {
                    continue;
                }
                ComponentName componentName = mDpm.getProfileOwnerAsUser(userInfo.getUserHandle());
                if (componentName == null) {
                    continue;
                }
                if (componentName.getPackageName().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public LauncherActivityInfoInternal resolveLauncherActivityInternal(
                String callingPackage, ComponentName component, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot resolve activity")) {
                return null;
            }

            if (component == null || component.getPackageName() == null) {
                // should not happen
                return null;
            }

            final int callingUid = injectBinderCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                ActivityInfo activityInfo =
                        mPackageManagerInternal.getActivityInfo(
                                component,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                callingUid,
                                user.getIdentifier());
                if (activityInfo == null) {
                    if (isArchivingEnabled()) {
                        return getMatchingArchivedAppActivityInfo(component, user);
                    }
                    return null;
                }
                final IncrementalStatesInfo incrementalStatesInfo =
                        mPackageManagerInternal.getIncrementalStatesInfo(
                                component.getPackageName(), callingUid, user.getIdentifier());
                if (incrementalStatesInfo == null) {
                    // package does not exist; should not happen
                    return null;
                }
                return new LauncherActivityInfoInternal(activityInfo, incrementalStatesInfo, user);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private @Nullable LauncherActivityInfoInternal getMatchingArchivedAppActivityInfo(
                @NonNull ComponentName component, UserHandle user) {
            List<LauncherActivityInfoInternal> archivedActivities =
                    generateLauncherActivitiesForArchivedApp(component.getPackageName(), user);
            if (archivedActivities.isEmpty()) {
                return null;
            }
            for (int i = 0; i < archivedActivities.size(); i++) {
                if (archivedActivities.get(i).getComponentName().equals(component)) {
                    return archivedActivities.get(i);
                }
            }
            Slog.w(
                    TAG,
                    TextUtils.formatSimple(
                            "Expected archived app component name: %s" + " is not available!",
                            component));
            return null;
        }

        @Override
        public ParceledListSlice getShortcutConfigActivities(
                String callingPackage, String packageName, UserHandle user)
                throws RemoteException {
            // Not supported for user-profiles with items restricted on home screen.
            if (!mShortcutServiceInternal.areShortcutsSupportedOnHomeScreen(user.getIdentifier())) {
                return null;
            }
            return queryActivitiesForUser(callingPackage,
                    new Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(packageName), user);
        }

        private ParceledListSlice<LauncherActivityInfoInternal> queryActivitiesForUser(
                String callingPackage, Intent intent, UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(), "Cannot retrieve activities")) {
                return null;
            }
            final int callingUid = injectBinderCallingUid();
            long ident = injectClearCallingIdentity();
            try {
                return new ParceledListSlice<>(queryIntentLauncherActivities(intent, callingUid,
                        user));
            } finally {
                injectRestoreCallingIdentity(ident);
            }
        }

        private boolean isPackageArchived(@NonNull String packageName, UserHandle user) {
            return !getApplicationInfoForArchivedApp(packageName, user).isEmpty();
        }

        @NonNull
        private List<LauncherActivityInfoInternal> generateLauncherActivitiesForArchivedApp(
                @Nullable String packageName, UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(), "Cannot retrieve activities")) {
                return List.of();
            }
            List<ApplicationInfo> applicationInfoList =
                    (packageName == null)
                            ? getApplicationInfoListForAllArchivedApps(user)
                            : getApplicationInfoForArchivedApp(packageName, user);
            List<LauncherActivityInfoInternal> launcherActivityList = new ArrayList<>();
            for (int i = 0; i < applicationInfoList.size(); i++) {
                ApplicationInfo applicationInfo = applicationInfoList.get(i);
                PackageStateInternal packageState =
                        mPackageManagerInternal.getPackageStateInternal(
                                applicationInfo.packageName);
                if (packageState == null) {
                    continue;
                }
                ArchiveState archiveState =
                        packageState.getUserStateOrDefault(user.getIdentifier()).getArchiveState();
                if (archiveState == null) {
                    Slog.w(
                            TAG,
                            TextUtils.formatSimple(
                                    "Expected package: %s to be archived but missing ArchiveState"
                                            + " in PackageState.",
                                    applicationInfo.packageName));
                    continue;
                }
                List<ArchiveState.ArchiveActivityInfo> archiveActivityInfoList =
                        archiveState.getActivityInfos();
                for (int j = 0; j < archiveActivityInfoList.size(); j++) {
                    launcherActivityList.add(
                            constructLauncherActivityInfoForArchivedApp(
                                    user, applicationInfo, archiveActivityInfoList.get(j)));
                }
            }
            return launcherActivityList;
        }

        private static LauncherActivityInfoInternal constructLauncherActivityInfoForArchivedApp(
                UserHandle user,
                ApplicationInfo applicationInfo,
                ArchiveState.ArchiveActivityInfo archiveActivityInfo) {
            ActivityInfo activityInfo = new ActivityInfo();
            activityInfo.isArchived = applicationInfo.isArchived;
            activityInfo.applicationInfo = applicationInfo;
            activityInfo.packageName =
                    archiveActivityInfo.getOriginalComponentName().getPackageName();
            activityInfo.name = archiveActivityInfo.getOriginalComponentName().getClassName();
            activityInfo.nonLocalizedLabel = archiveActivityInfo.getTitle();

            return new LauncherActivityInfoInternal(
                    activityInfo,
                    new IncrementalStatesInfo(
                            false /* isLoading */, 0 /* progress */, 0 /* loadingCompletedTime */),
                    user);
        }

        @NonNull
        private List<ApplicationInfo> getApplicationInfoListForAllArchivedApps(UserHandle user) {
            final int callingUid = injectBinderCallingUid();
            List<ApplicationInfo> installedApplicationInfoList =
                    mPackageManagerInternal.getInstalledApplicationsCrossUser(
                            PackageManager.MATCH_ARCHIVED_PACKAGES,
                            user.getIdentifier(),
                            callingUid);
            List<ApplicationInfo> archivedApplicationInfos = new ArrayList<>();
            for (int i = 0; i < installedApplicationInfoList.size(); i++) {
                ApplicationInfo installedApplicationInfo = installedApplicationInfoList.get(i);
                if (installedApplicationInfo != null && installedApplicationInfo.isArchived) {
                    archivedApplicationInfos.add(installedApplicationInfo);
                }
            }
            return archivedApplicationInfos;
        }

        @NonNull
        private List<ApplicationInfo> getApplicationInfoForArchivedApp(
                @NonNull String packageName, UserHandle user) {
            final int callingUid = injectBinderCallingUid();
            ApplicationInfo applicationInfo = Binder.withCleanCallingIdentity(() ->
                    mPackageManagerInternal.getApplicationInfo(
                            packageName,
                            PackageManager.MATCH_ARCHIVED_PACKAGES,
                            callingUid,
                            user.getIdentifier()));
            if (applicationInfo == null || !applicationInfo.isArchived) {
                return Collections.EMPTY_LIST;
            }
            return List.of(applicationInfo);
        }

        private List<LauncherActivityInfoInternal> queryIntentLauncherActivities(
                Intent intent, int callingUid, UserHandle user) {
            final List<ResolveInfo> apps = mPackageManagerInternal.queryIntentActivities(intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    callingUid, user.getIdentifier());
            final int numResolveInfos = apps.size();
            List<LauncherActivityInfoInternal> results = new ArrayList<>();
            for (int i = 0; i < numResolveInfos; i++) {
                final ResolveInfo ri = apps.get(i);
                final String packageName = ri.activityInfo.packageName;
                if (packageName == null) {
                    // should not happen
                    continue;
                }
                final IncrementalStatesInfo incrementalStatesInfo =
                        mPackageManagerInternal.getIncrementalStatesInfo(packageName, callingUid,
                                user.getIdentifier());
                if (incrementalStatesInfo == null) {
                    // package doesn't exist any more; should not happen
                    continue;
                }
                results.add(new LauncherActivityInfoInternal(ri.activityInfo,
                        incrementalStatesInfo, user));
            }
            return results;
        }

        @Override
        public IntentSender getShortcutConfigActivityIntent(String callingPackage,
                ComponentName component, UserHandle user) throws RemoteException {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(user.getIdentifier(), "Cannot check package")) {
                return null;
            }
            Objects.requireNonNull(component);

            // All right, create the sender.
            final int callingUid = injectBinderCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                Intent packageIntent = new Intent(Intent.ACTION_CREATE_SHORTCUT)
                        .setPackage(component.getPackageName());
                List<ResolveInfo> apps =
                        mPackageManagerInternal.queryIntentActivities(packageIntent,
                                packageIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                callingUid, user.getIdentifier());
                // ensure that the component is present in the list
                if (!apps.stream().anyMatch(
                        ri -> component.getClassName().equals(ri.activityInfo.name))) {
                    return null;
                }

                Intent intent = new Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(component);
                final PendingIntent pi = PendingIntent.getActivityAsUser(
                        mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT,
                        null, user);
                return pi == null ? null : pi.getIntentSender();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Returns the intents for a specific shortcut.
         */
        @Nullable
        @Override
        public PendingIntent getShortcutIntent(@NonNull final String callingPackage,
                @NonNull final String packageName, @NonNull final String shortcutId,
                @Nullable final Bundle opts, @NonNull final UserHandle user)
                throws RemoteException {
            Objects.requireNonNull(callingPackage);
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(shortcutId);
            Objects.requireNonNull(user);

            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(user.getIdentifier(), "Cannot get shortcuts")) {
                return null;
            }

            final AndroidFuture<Intent[]> ret = new AndroidFuture<>();
            Intent[] intents;
            mShortcutServiceInternal.createShortcutIntentsAsync(getCallingUserId(),
                    callingPackage, packageName, shortcutId, user.getIdentifier(),
                    injectBinderCallingPid(), injectBinderCallingUid(), ret);
            try {
                intents = ret.get();
            } catch (InterruptedException | ExecutionException e) {
                return null;
            }
            if (intents == null || intents.length == 0) {
                return null;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return injectCreatePendingIntent(0 /* requestCode */, intents,
                        FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT, opts, packageName,
                        mPackageManagerInternal.getPackageUid(
                                packageName, PackageManager.MATCH_DIRECT_BOOT_AUTO,
                                user.getIdentifier()));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public boolean isPackageEnabled(String callingPackage, String packageName, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot check package")) {
                return false;
            }

            final int callingUid = injectBinderCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                long callingFlag =
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
                if (isArchivingEnabled()) {
                    callingFlag |= PackageManager.MATCH_ARCHIVED_PACKAGES;
                }
                final PackageInfo info =
                        mPackageManagerInternal.getPackageInfo(
                                packageName, callingFlag, callingUid, user.getIdentifier());
                return info != null
                        && (info.applicationInfo.enabled || info.applicationInfo.isArchived);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public Bundle getSuspendedPackageLauncherExtras(String packageName,
                UserHandle user) {
            final int callingUid = injectBinderCallingUid();
            final int userId = user.getIdentifier();
            if (!canAccessProfile(userId, "Cannot get launcher extras")) {
                return null;
            }
            if (mPackageManagerInternal.filterAppAccess(packageName, callingUid, userId)) {
                return null;
            }
            return mPackageManagerInternal.getSuspendedPackageLauncherExtras(packageName, userId);
        }

        @Override
        public ApplicationInfo getApplicationInfo(
                String callingPackage, String packageName, int flags, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot check package")) {
                return null;
            }

            final int callingUid = injectBinderCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                final ApplicationInfo info = mPackageManagerInternal.getApplicationInfo(packageName,
                        flags, callingUid, user.getIdentifier());
                return info;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public LauncherApps.AppUsageLimit getAppUsageLimit(String callingPackage,
                String packageName, UserHandle user) {
            verifyCallingPackage(callingPackage);
            if (!canAccessProfile(user.getIdentifier(), "Cannot access usage limit")) {
                return null;
            }
            if (!mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid())) {
                throw new SecurityException("Caller is not the recents app");
            }

            final UsageStatsManagerInternal.AppUsageLimitData data =
                    mUsageStatsManagerInternal.getAppUsageLimit(packageName, user);
            if (data == null) {
                return null;
            }
            return new LauncherApps.AppUsageLimit(
                    data.getTotalUsageLimit(), data.getUsageRemaining());
        }

        private void ensureShortcutPermission(@NonNull String callingPackage) {
            ensureShortcutPermission(injectBinderCallingUid(), injectBinderCallingPid(),
                    callingPackage);
        }

        private void ensureShortcutPermission(int callerUid, int callerPid,
                @NonNull String callingPackage) {
            verifyCallingPackage(callingPackage, callerUid);
            if (!mShortcutServiceInternal.hasShortcutHostPermission(UserHandle.getUserId(callerUid),
                    callingPackage, callerPid, callerUid)) {
                throw new SecurityException("Caller can't access shortcut information");
            }
        }

        private void ensureStrictAccessShortcutsPermission(@NonNull String callingPackage) {
            verifyCallingPackage(callingPackage);
            if (!injectHasAccessShortcutsPermission(injectBinderCallingPid(),
                    injectBinderCallingUid())) {
                throw new SecurityException("Caller can't access shortcut information");
            }
        }

        /**
         * Returns true if the caller has the "ACCESS_SHORTCUTS" permission.
         */
        @VisibleForTesting
        boolean injectHasAccessShortcutsPermission(int callingPid, int callingUid) {
            return mContext.checkPermission(android.Manifest.permission.ACCESS_SHORTCUTS,
                    callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
        }

        /**
         * Returns true if the caller has the "INTERACT_ACROSS_USERS_FULL" permission.
         */
        @VisibleForTesting
        boolean injectHasInteractAcrossUsersFullPermission(int callingPid, int callingUid) {
            return mContext.checkPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
        }

        @VisibleForTesting
        PendingIntent injectCreatePendingIntent(int requestCode, @NonNull Intent[] intents,
                int flags, Bundle options, String ownerPackage, int ownerUserId) {
            return mActivityManagerInternal.getPendingIntentActivityAsApp(requestCode, intents,
                    flags, null /* options */, ownerPackage, ownerUserId);
        }

        @Override
        public ParceledListSlice getShortcuts(@NonNull final String callingPackage,
                @NonNull final ShortcutQueryWrapper query, @NonNull final UserHandle targetUser) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot get shortcuts")) {
                Log.e(TAG, "return empty shortcuts because callingPackage " + callingPackage
                        + " cannot access user " + targetUser.getIdentifier());
                return new ParceledListSlice<>(Collections.EMPTY_LIST);
            }

            final long changedSince = query.getChangedSince();
            final String packageName = query.getPackage();
            final List<String> shortcutIds = query.getShortcutIds();
            final List<LocusId> locusIds = query.getLocusIds();
            final ComponentName componentName = query.getActivity();
            final int flags = query.getQueryFlags();
            if (shortcutIds != null && packageName == null) {
                throw new IllegalArgumentException(
                        "To query by shortcut ID, package name must also be set");
            }
            if (locusIds != null && packageName == null) {
                throw new IllegalArgumentException(
                        "To query by locus ID, package name must also be set");
            }
            if ((query.getQueryFlags() & ShortcutQuery.FLAG_GET_PERSONS_DATA) != 0) {
                ensureStrictAccessShortcutsPermission(callingPackage);
            }

            // TODO(b/29399275): Eclipse compiler requires explicit List<ShortcutInfo> cast below.
            return new ParceledListSlice<>((List<ShortcutInfo>)
                    mShortcutServiceInternal.getShortcuts(getCallingUserId(),
                            callingPackage, changedSince, packageName, shortcutIds, locusIds,
                            componentName, flags, targetUser.getIdentifier(),
                            injectBinderCallingPid(), injectBinderCallingUid()));
        }

        @Override
        public void getShortcutsAsync(@NonNull final String callingPackage,
                @NonNull final ShortcutQueryWrapper query, @NonNull final UserHandle targetUser,
                @NonNull final AndroidFuture<List<ShortcutInfo>> cb) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot get shortcuts")) {
                cb.complete(Collections.EMPTY_LIST);
                return;
            }

            final long changedSince = query.getChangedSince();
            final String packageName = query.getPackage();
            final List<String> shortcutIds = query.getShortcutIds();
            final List<LocusId> locusIds = query.getLocusIds();
            final ComponentName componentName = query.getActivity();
            final int flags = query.getQueryFlags();
            if (shortcutIds != null && packageName == null) {
                throw new IllegalArgumentException(
                        "To query by shortcut ID, package name must also be set");
            }
            if (locusIds != null && packageName == null) {
                throw new IllegalArgumentException(
                        "To query by locus ID, package name must also be set");
            }
            if ((query.getQueryFlags() & ShortcutQuery.FLAG_GET_PERSONS_DATA) != 0) {
                ensureStrictAccessShortcutsPermission(callingPackage);
            }

            mShortcutServiceInternal.getShortcutsAsync(getCallingUserId(),
                    callingPackage, changedSince, packageName, shortcutIds, locusIds,
                    componentName, flags, targetUser.getIdentifier(),
                    injectBinderCallingPid(), injectBinderCallingUid(), cb);
        }

        @Override
        public void registerShortcutChangeCallback(@NonNull final String callingPackage,
                @NonNull final ShortcutQueryWrapper query,
                @NonNull final IShortcutChangeCallback callback) {

            ensureShortcutPermission(callingPackage);

            if (query.getShortcutIds() != null && query.getPackage() == null) {
                throw new IllegalArgumentException(
                        "To query by shortcut ID, package name must also be set");
            }
            if (query.getLocusIds() != null && query.getPackage() == null) {
                throw new IllegalArgumentException(
                        "To query by locus ID, package name must also be set");
            }

            UserHandle user = UserHandle.of(injectCallingUserId());
            if (injectHasInteractAcrossUsersFullPermission(injectBinderCallingPid(),
                    injectBinderCallingUid())) {
                user = null;
            }

            mShortcutChangeHandler.addShortcutChangeCallback(callback, query, user);
        }

        @Override
        public void unregisterShortcutChangeCallback(String callingPackage,
                IShortcutChangeCallback callback) {
            ensureShortcutPermission(callingPackage);

            mShortcutChangeHandler.removeShortcutChangeCallback(callback);
        }

        @Override
        public void pinShortcuts(String callingPackage, String packageName, List<String> ids,
                UserHandle targetUser) {
            if (!mShortcutServiceInternal
                    .areShortcutsSupportedOnHomeScreen(targetUser.getIdentifier())) {
                // Requires strict ACCESS_SHORTCUTS permission for user-profiles with items
                // restricted on home screen.
                ensureStrictAccessShortcutsPermission(callingPackage);
            } else {
                ensureShortcutPermission(callingPackage);
            }
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot pin shortcuts")) {
                return;
            }

            mShortcutServiceInternal.pinShortcuts(getCallingUserId(),
                    callingPackage, packageName, ids, targetUser.getIdentifier());
        }

        @Override
        public void cacheShortcuts(String callingPackage, String packageName, List<String> ids,
                UserHandle targetUser, int cacheFlags) {
            ensureStrictAccessShortcutsPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot cache shortcuts")) {
                return;
            }

            mShortcutServiceInternal.cacheShortcuts(
                    getCallingUserId(), callingPackage, packageName, ids,
                    targetUser.getIdentifier(), toShortcutsCacheFlags(cacheFlags));
        }

        @Override
        public void uncacheShortcuts(String callingPackage, String packageName, List<String> ids,
                UserHandle targetUser, int cacheFlags) {
            ensureStrictAccessShortcutsPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot uncache shortcuts")) {
                return;
            }

            mShortcutServiceInternal.uncacheShortcuts(
                    getCallingUserId(), callingPackage, packageName, ids,
                    targetUser.getIdentifier(), toShortcutsCacheFlags(cacheFlags));
        }

        @Override
        public int getShortcutIconResId(String callingPackage, String packageName, String id,
                int targetUserId) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUserId, "Cannot access shortcuts")) {
                return 0;
            }

            return mShortcutServiceInternal.getShortcutIconResId(getCallingUserId(),
                    callingPackage, packageName, id, targetUserId);
        }

        @Override
        public ParcelFileDescriptor getShortcutIconFd(String callingPackage,
                String packageName, String id, int targetUserId) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUserId, "Cannot access shortcuts")) {
                return null;
            }

            final AndroidFuture<ParcelFileDescriptor> ret = new AndroidFuture<>();
            mShortcutServiceInternal.getShortcutIconFdAsync(getCallingUserId(),
                    callingPackage, packageName, id, targetUserId, ret);
            try {
                return ret.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getShortcutIconUri(String callingPackage, String packageName,
                String shortcutId, int userId) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(userId, "Cannot access shortcuts")) {
                return null;
            }

            final AndroidFuture<String> ret = new AndroidFuture<>();
            mShortcutServiceInternal.getShortcutIconUriAsync(getCallingUserId(), callingPackage,
                    packageName, shortcutId, userId, ret);
            try {
                return ret.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean hasShortcutHostPermission(String callingPackage) {
            verifyCallingPackage(callingPackage);
            return mShortcutServiceInternal.hasShortcutHostPermission(getCallingUserId(),
                    callingPackage, injectBinderCallingPid(), injectBinderCallingUid());
        }

        @Override
        @NonNull
        public Map<String, LauncherActivityInfoInternal> getActivityOverrides(String callingPackage,
                int userId) {
            ensureShortcutPermission(callingPackage);
            int callingUid = Binder.getCallingUid();
            final long callerIdentity = Binder.clearCallingIdentity();
            try {
                Map<String, LauncherActivityInfoInternal> shortcutOverridesInfo = new ArrayMap<>();
                UserHandle managedUserHandle = getManagedProfile(userId);
                if (managedUserHandle == null) {
                    return shortcutOverridesInfo;
                }

                Map<String, String> packagesToOverride =
                        DevicePolicyCache.getInstance().getLauncherShortcutOverrides();
                for (Map.Entry<String, String> packageNames : packagesToOverride.entrySet()) {
                    Intent intent = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(packageNames.getValue());

                    List<LauncherActivityInfoInternal> possibleShortcutOverrides =
                            queryIntentLauncherActivities(
                                    intent,
                                    callingUid,
                                    managedUserHandle
                            );

                    if (!possibleShortcutOverrides.isEmpty()) {
                        shortcutOverridesInfo.put(packageNames.getKey(),
                                possibleShortcutOverrides.get(0));
                    }
                }
                return shortcutOverridesInfo;
            } finally {
                Binder.restoreCallingIdentity(callerIdentity);
            }
        }


        @Nullable
        private UserHandle getManagedProfile(int userId) {
            for (UserInfo profile : mUm.getProfiles(userId)) {
                if (profile.isManagedProfile()) {
                    return profile.getUserHandle();
                }
            }
            return null;
        }

        @Override
        public boolean startShortcut(String callingPackage, String packageName, String featureId,
                String shortcutId, Rect sourceBounds, Bundle startActivityOptions,
                int targetUserId) {
            return startShortcutInner(injectBinderCallingUid(), injectBinderCallingPid(),
                    injectCallingUserId(), callingPackage, packageName, featureId, shortcutId,
                    sourceBounds, startActivityOptions, targetUserId);
        }

        private boolean startShortcutInner(int callerUid, int callerPid, int callingUserId,
                String callingPackage, String packageName, String featureId, String shortcutId,
                Rect sourceBounds, Bundle startActivityOptions, int targetUserId) {
            verifyCallingPackage(callingPackage, callerUid);
            if (!canAccessProfile(targetUserId, "Cannot start activity")) {
                return false;
            }

            // Even without the permission, pinned shortcuts are always launchable.
            if (!mShortcutServiceInternal.isPinnedByCaller(callingUserId,
                    callingPackage, packageName, shortcutId, targetUserId)) {
                ensureShortcutPermission(callerUid, callerPid, callingPackage);
            }

            final AndroidFuture<Intent[]> ret = new AndroidFuture<>();
            Intent[] intents;
            mShortcutServiceInternal.createShortcutIntentsAsync(getCallingUserId(), callingPackage,
                    packageName, shortcutId, targetUserId,
                    injectBinderCallingPid(), injectBinderCallingUid(), ret);
            try {
                intents = ret.get();
            } catch (InterruptedException | ExecutionException e) {
                return false;
            }
            if (intents == null || intents.length == 0) {
                return false;
            }
            // Note the target activity doesn't have to be exported.

            // Flag for bubble
            ActivityOptions options = ActivityOptions.fromBundle(startActivityOptions);
            if (options != null) {
                if (options.isApplyActivityFlagsForBubbles()) {
                    // Flag for bubble to make behaviour match documentLaunchMode=always.
                    intents[0].addFlags(FLAG_ACTIVITY_NEW_DOCUMENT);
                    intents[0].addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                }
                if (options.isApplyMultipleTaskFlagForShortcut()) {
                    intents[0].addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                }
                if (options.isApplyNoUserActionFlagForShortcut()) {
                    intents[0].addFlags(FLAG_ACTIVITY_NO_USER_ACTION);
                }
            }
            intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intents[0].setSourceBounds(sourceBounds);

            // Replace theme for splash screen
            final String splashScreenThemeResName =
                    mShortcutServiceInternal.getShortcutStartingThemeResName(callingUserId,
                            callingPackage, packageName, shortcutId, targetUserId);
            if (splashScreenThemeResName != null && !splashScreenThemeResName.isEmpty()) {
                if (startActivityOptions == null) {
                    startActivityOptions = new Bundle();
                }
                startActivityOptions.putString(KEY_SPLASH_SCREEN_THEME, splashScreenThemeResName);
            }
            return startShortcutIntentsAsPublisher(
                    intents, packageName, featureId, startActivityOptions, targetUserId);
        }

        private boolean startShortcutIntentsAsPublisher(@NonNull Intent[] intents,
                @NonNull String publisherPackage, @Nullable String publishedFeatureId,
                Bundle startActivityOptions, int userId) {
            final int code;
            try {
                code = mActivityTaskManagerInternal.startActivitiesAsPackage(publisherPackage,
                        publishedFeatureId, userId, intents,
                        getActivityOptionsForLauncher(startActivityOptions));
                if (ActivityManager.isStartResultSuccessful(code)) {
                    return true; // Success
                } else {
                    Log.e(TAG, "Couldn't start activity, code=" + code);
                }
                return false;
            } catch (SecurityException e) {
                if (DEBUG) {
                    Slog.d(TAG, "SecurityException while launching intent", e);
                }
                return false;
            }
        }

        private Bundle getActivityOptionsForLauncher(Bundle startActivityOptions) {
            // starting a shortcut implies the user's consent, so grant the launchers/senders BAL
            // privileges (unless the caller explicitly defined the behavior)
            if (startActivityOptions == null) {
                return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                                MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            }
            ActivityOptions activityOptions = ActivityOptions.fromBundle(startActivityOptions);
            if (activityOptions.getPendingIntentBackgroundActivityStartMode()
                    == MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
                // only override if the property was not explicitly set
                return activityOptions.setPendingIntentBackgroundActivityStartMode(
                        MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            }
            return startActivityOptions;
        }

        @Override
        public boolean isActivityEnabled(
                String callingPackage, ComponentName component, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot check component")) {
                return false;
            }
            if (isArchivingEnabled() && component != null && component.getPackageName() != null) {
                List<LauncherActivityInfoInternal> archiveActivities =
                        generateLauncherActivitiesForArchivedApp(component.getPackageName(), user);
                if (!archiveActivities.isEmpty()) {
                    for (int i = 0; i < archiveActivities.size(); i++) {
                        if (archiveActivities.get(i).getComponentName().equals(component)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
            final int callingUid = injectBinderCallingUid();
            final int state = mPackageManagerInternal.getComponentEnabledSetting(component,
                    callingUid, user.getIdentifier());
            switch (state) {
                case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                    break; // Need to check the manifest's enabled state.
                case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                    return true;
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                    return false;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                final ActivityInfo info = mPackageManagerInternal.getActivityInfo(component,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        callingUid, user.getIdentifier());
                // Note we don't check "exported" because if the caller has the same UID as the
                // callee's UID, it can still be launched.
                // (If an app doesn't export a front door activity and causes issues with the
                // launcher, that's just the app's bug.)
                return info != null && info.isEnabled();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void startSessionDetailsActivityAsUser(IApplicationThread caller,
                String callingPackage, String callingFeatureId, SessionInfo sessionInfo,
                Rect sourceBounds, Bundle opts, UserHandle userHandle) throws RemoteException {
            int userId = userHandle.getIdentifier();
            if (!canAccessProfile(userId, "Cannot start details activity")) {
                return;
            }

            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setData(new Uri.Builder()
                            .scheme("market")
                            .authority("details")
                            .appendQueryParameter("id", sessionInfo.appPackageName)
                            .build())
                    .putExtra(Intent.EXTRA_REFERRER, new Uri.Builder().scheme("android-app")
                            .authority(callingPackage).build());
            i.setSourceBounds(sourceBounds);

            mActivityTaskManagerInternal.startActivityAsUser(caller, callingPackage,
                    callingFeatureId, i, /* resultTo= */ null, Intent.FLAG_ACTIVITY_NEW_TASK,
                    getActivityOptionsForLauncher(opts), userId);
        }

        @Override
        public PendingIntent getActivityLaunchIntent(String callingPackage, ComponentName component,
                UserHandle user) {
            try {
                Log.d(TAG,
                        "getActivityLaunchIntent callingPackage=" + callingPackage + " component="
                                + component + " user=" + user);
            } catch (Exception e) {
                Log.e(TAG, "getActivityLaunchIntent is called and error occurred when"
                        + " printing the logs", e);
            }
            if (mContext.checkPermission(android.Manifest.permission.START_TASKS_FROM_RECENTS,
                    injectBinderCallingPid(), injectBinderCallingUid())
                            != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "getActivityLaunchIntent no permission callingPid="
                        + injectBinderCallingPid() + " callingUid=" + injectBinderCallingUid());
                throw new SecurityException("Permission START_TASKS_FROM_RECENTS required");
            }
            if (!canAccessProfile(user.getIdentifier(), "Cannot start activity")) {
                Log.d(TAG, "getActivityLaunchIntent cannot access profile user="
                        + user.getIdentifier());
                throw new ActivityNotFoundException("Activity could not be found");
            }

            final Intent launchIntent = getMainActivityLaunchIntent(component, user,
                    false /* includeArchivedApps */);
            if (launchIntent == null) {
                Log.d(TAG, "getActivityLaunchIntent cannot access profile user="
                        + user.getIdentifier() + " component=" + component);
                throw new SecurityException("Attempt to launch activity without "
                        + " category Intent.CATEGORY_LAUNCHER " + component);
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                // If we reach here, we've verified that the caller has access to the profile and
                // is launching an exported activity with CATEGORY_LAUNCHER so we can clear the
                // calling identity to mirror the startActivityAsUser() call which does not validate
                // the calling user
                return PendingIntent.getActivityAsUser(mContext, 0 /* requestCode */, launchIntent,
                        FLAG_MUTABLE, null /* opts */, user);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public @Nullable LauncherUserInfo getLauncherUserInfo(@NonNull UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(),
                    "Can't access LauncherUserInfo for another user")) {
                return null;
            }
            long ident = injectClearCallingIdentity();
            try {
                return mUserManagerInternal.getLauncherUserInfo(user.getIdentifier());
            } finally {
                injectRestoreCallingIdentity(ident);
            }
        }

        @Override
        @NonNull
        public List<String> getPreInstalledSystemPackages(UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(),
                    "Can't access preinstalled packages for another user")) {
                return new ArrayList<>();
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                String userType = mUm.getUserInfo(user.getIdentifier()).userType;
                Set<String> preInstalledPackages = mUm.getPreInstallableSystemPackages(userType);
                if (preInstalledPackages == null) {
                    return new ArrayList<>();
                }
                return List.copyOf(preInstalledPackages);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public @Nullable IntentSender getAppMarketActivityIntent(@NonNull String callingPackage,
                @Nullable String packageName, @NonNull UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(),
                    "Can't access AppMarketActivity for another user")) {
                return null;
            }
            final int callingUser = getCallingUserId();
            final long identity = Binder.clearCallingIdentity();

            try {
                if (packageName == null) {
                    return buildAppMarketIntentSenderForUser(user);
                }

                String installerPackageName = getInstallerPackage(packageName, callingUser);
                if (installerPackageName == null
                        || mPackageManagerInternal.getPackageUid(
                                        installerPackageName, /* flags= */ 0, user.getIdentifier())
                                < 0) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "Can't find installer for "
                                        + packageName
                                        + " in user: "
                                        + user.getIdentifier());
                    }
                    return buildAppMarketIntentSenderForUser(user);
                }

                Intent packageInfoIntent =
                        buildMarketPackageInfoIntent(
                                packageName, installerPackageName, callingPackage);
                if (mPackageManagerInternal
                        .queryIntentActivities(
                                packageInfoIntent,
                                packageInfoIntent.resolveTypeIfNeeded(
                                        mContext.getContentResolver()),
                                PackageManager.MATCH_ALL,
                                Process.myUid(),
                                user.getIdentifier())
                        .isEmpty()) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "Can't resolve package info intent for package "
                                        + packageName
                                        + " and installer:  "
                                        + installerPackageName);
                    }
                    return buildAppMarketIntentSenderForUser(user);
                }

                return buildIntentSenderForUser(packageInfoIntent, user);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public @Nullable IntentSender getPrivateSpaceSettingsIntent() {
            if (!canAccessHiddenProfile(getCallingUid(), getCallingPid())) {
                Slog.e(TAG, "Caller cannot access hidden profiles");
                return null;
            }
            final int callingUser = getCallingUserId();
            final int callingUid = getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                Intent psSettingsIntent = new Intent(PS_SETTINGS_INTENT);
                psSettingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                List<ResolveInfo> ri = mPackageManagerInternal.queryIntentActivities(
                        psSettingsIntent,
                        psSettingsIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                        PackageManager.MATCH_SYSTEM_ONLY, callingUid, callingUser);
                if (ri.isEmpty()) {
                    return null;
                }
                final PendingIntent pi = PendingIntent.getActivityAsUser(mContext,
                        /* requestCode */ 0,
                        psSettingsIntent,
                        PendingIntent.FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT,
                        null,
                        UserHandle.of(callingUser));
                return pi == null ? null : pi.getIntentSender();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Nullable
        private IntentSender buildAppMarketIntentSenderForUser(@NonNull UserHandle user) {
            Intent appMarketIntent = new Intent(Intent.ACTION_MAIN);
            appMarketIntent.addCategory(Intent.CATEGORY_APP_MARKET);
            appMarketIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return buildIntentSenderForUser(appMarketIntent, user);
        }

        @Nullable
        private IntentSender buildIntentSenderForUser(
                @NonNull Intent intent, @NonNull UserHandle user) {
            final PendingIntent pi =
                    PendingIntent.getActivityAsUser(
                            mContext,
                            /* requestCode */ 0,
                            intent,
                            PendingIntent.FLAG_IMMUTABLE
                                    | FLAG_UPDATE_CURRENT,
                            /* options */ null,
                            user);
            return pi == null ? null : pi.getIntentSender();
        }

        @Nullable
        private String getInstallerPackage(@NonNull String packageName, int callingUserId) {
            String installerPackageName = null;
            try {
                InstallSourceInfo info = mIPM.getInstallSourceInfo(packageName, callingUserId);
                if (info == null) {
                    return installerPackageName;
                }
                installerPackageName = info.getInstallingPackageName();
            } catch (RemoteException re) {
                Slog.e(TAG, "Couldn't find installer for " + packageName, re);
            }

            return installerPackageName;
        }

        @NonNull
        private Intent buildMarketPackageInfoIntent(
                @NonNull String packageName,
                @NonNull String installerPackageName,
                @NonNull String callingPackage) {
            return new Intent(Intent.ACTION_VIEW)
                    .setData(
                            new Uri.Builder()
                                    .scheme("market")
                                    .authority("details")
                                    .appendQueryParameter("id", packageName)
                                    .build())
                    .putExtra(
                            Intent.EXTRA_REFERRER,
                            new Uri.Builder()
                                    .scheme("android-app")
                                    .authority(callingPackage)
                                    .build())
                    .setPackage(installerPackageName)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        @Override
        public void startActivityAsUser(IApplicationThread caller, String callingPackage,
                String callingFeatureId, ComponentName component, Rect sourceBounds,
                Bundle opts, UserHandle user) throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot start activity")) {
                return;
            }

            Intent launchIntent = getMainActivityLaunchIntent(component, user,
                    true /* includeArchivedApps */);
            if (launchIntent == null) {
                throw new SecurityException("Attempt to launch activity without "
                        + " category Intent.CATEGORY_LAUNCHER " + component);
            }
            launchIntent.setSourceBounds(sourceBounds);

            mActivityTaskManagerInternal.startActivityAsUser(caller, callingPackage,
                    callingFeatureId, launchIntent, /* resultTo= */ null,
                    Intent.FLAG_ACTIVITY_NEW_TASK, getActivityOptionsForLauncher(opts),
                    user.getIdentifier());
        }

        /**
         * Returns the main activity launch intent for the given component package.
         */
        private Intent getMainActivityLaunchIntent(ComponentName component, UserHandle user,
                boolean includeArchivedApps) {
            Intent launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            launchIntent.setPackage(component.getPackageName());

            boolean canLaunch = false;

            final int callingUid = injectBinderCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                // Check that the component actually has Intent.CATEGORY_LAUCNCHER
                // as calling startActivityAsUser ignores the category and just
                // resolves based on the component if present.
                final List<ResolveInfo> apps = mPackageManagerInternal.queryIntentActivities(
                        launchIntent,
                        launchIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        callingUid, user.getIdentifier());
                final int size = apps.size();
                for (int i = 0; i < size; ++i) {
                    ActivityInfo activityInfo = apps.get(i).activityInfo;
                    if (activityInfo.packageName.equals(component.getPackageName()) &&
                            activityInfo.name.equals(component.getClassName())) {
                        if (!activityInfo.exported) {
                            throw new SecurityException("Cannot launch non-exported components "
                                    + component);
                        }

                        // Found an activity with category launcher that matches
                        // this component so ok to launch.
                        launchIntent.setPackage(null);
                        launchIntent.setComponent(component);
                        canLaunch = true;
                        break;
                    }
                }
                if (!canLaunch
                        && includeArchivedApps
                        && isArchivingEnabled()
                        && getMatchingArchivedAppActivityInfo(component, user) != null) {
                    launchIntent.setPackage(null);
                    launchIntent.setComponent(component);
                    canLaunch = true;
                }
                if (!canLaunch) {
                    try {
                        Log.w(TAG, "getMainActivityLaunchIntent return null because it can't launch"
                                + " component=" + component + " user=" + user + " appsSize=" + size
                                + " includeArchivedApps=" + includeArchivedApps
                                + " isArchivingEnabled=" + isArchivingEnabled()
                                + " matchingArchivedAppActivityInfo="
                                + getMatchingArchivedAppActivityInfo(component, user));
                    } catch (Exception e) {
                        Log.e(TAG, "getMainActivityLaunchIntent return null and error occurred when"
                                + " printing the logs", e);
                    }
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return launchIntent;
        }

        @Override
        public void showAppDetailsAsUser(IApplicationThread caller,
                String callingPackage, String callingFeatureId, ComponentName component,
                Rect sourceBounds, Bundle opts, UserHandle user) throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot show app details")) {
                return;
            }

            final Intent intent;
            final long ident = Binder.clearCallingIdentity();
            try {
                String packageName = component.getPackageName();
                int uId = -1;
                try {
                    uId = mContext.getPackageManager().getApplicationInfo(
                            packageName, PackageManager.MATCH_ANY_USER).uid;
                } catch (PackageManager.NameNotFoundException e) {
                    Log.d(TAG, "package not found: " + e);
                }
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null));
                intent.putExtra("uId", uId);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.setSourceBounds(sourceBounds);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            mActivityTaskManagerInternal.startActivityAsUser(caller, callingPackage,
                    callingFeatureId, intent, /* resultTo= */ null, Intent.FLAG_ACTIVITY_NEW_TASK,
                    getActivityOptionsForLauncher(opts), user.getIdentifier());
        }

        @Override
        public void onShellCommand(FileDescriptor in, @NonNull FileDescriptor out,
                @NonNull FileDescriptor err, @Nullable String[] args, ShellCallback cb,
                @Nullable ResultReceiver receiver) {
            final int callingUid = injectBinderCallingUid();
            if (!(callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID)) {
                throw new SecurityException("Caller must be shell");
            }

            final long token = injectClearCallingIdentity();
            try {
                int status = (new LauncherAppsShellCommand())
                        .exec(this, in, out, err, args, cb, receiver);
                if (receiver != null) {
                    receiver.send(status, null);
                }
            } finally {
                injectRestoreCallingIdentity(token);
            }
        }

        /** Handles Shell commands for LauncherAppsService */
        private class LauncherAppsShellCommand extends ShellCommand {
            @Override
            public int onCommand(@Nullable String cmd) {
                if ("dump-view-hierarchies".equals(cmd)) {
                    dumpViewCaptureDataToShell();
                    return 0;
                } else {
                    return handleDefaultCommands(cmd);
                }
            }

            private void dumpViewCaptureDataToShell() {
                try (ZipOutputStream zipOs = new ZipOutputStream(getRawOutputStream())) {
                    forEachViewCaptureWindow((fileName, is) -> {
                        try {
                            zipOs.putNextEntry(new ZipEntry("FS" + fileName));
                            transferViewCaptureData(is, zipOs);
                            zipOs.closeEntry();
                        } catch (IOException e) {
                            getErrPrintWriter().write("Failed to output " + fileName
                                    + " data to shell: " + e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    getErrPrintWriter().write("Failed to create or close zip output stream: "
                            + e.getMessage());
                }
            }

            @Override
            public void onHelp() {
                final PrintWriter pw = getOutPrintWriter();
                pw.println("Usage: cmd launcherapps COMMAND [options ...]");
                pw.println();
                pw.println("cmd launcherapps dump-view-hierarchies");
                pw.println("    Output captured view hierarchies. Files will be generated in ");
                pw.println("    `"  + WM_TRACE_DIR + "`. After pulling the data to your device,");
                pw.println("     you can upload / visualize it at `go/winscope`.");
                pw.println();
            }
        }

        /**
         * Using a pipe, outputs view capture data to the wmtrace dir
         */
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
                @Nullable String[] args) {
            super.dump(fd, pw, args);

            // Before the wmtrace directory is picked up by dumpstate service, some processes need
            // to write their data to that location. They can do that via these dumpCallbacks.
            forEachViewCaptureWindow(this::dumpViewCaptureDataToWmTrace);
        }

        private void dumpViewCaptureDataToWmTrace(@NonNull String fileName,
                @NonNull InputStream is) {
            Path outPath = Paths.get(fileName);
            try (OutputStream os = Files.newOutputStream(outPath, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                transferViewCaptureData(is, os);
                Files.setPosixFilePermissions(outPath, WM_TRACE_FILE_PERMISSIONS);
            } catch (IOException e) {
                Log.d(TAG, "failed to write data to " + fileName + " in wmtrace dir", e);
            }
        }

        /**
         * Raw input stream reads hang on the final read when transferring data in via the pipe.
         * The fix used below is to count and read the exact amount of bytes being sent.
         */
        private void transferViewCaptureData(InputStream is, OutputStream os) throws IOException {
            DataInputStream dataInputStream = new DataInputStream(is);
            new SizedInputStream(dataInputStream, dataInputStream.readInt()).transferTo(os);
        }

        /**
         * IDumpCallback.onDump alerts the in-process ViewCapture instance to start sending data
         * to LauncherAppsService via the pipe's input provided. This data (as well as an output
         * file name) is provided to the consumer via an InputStream to output where it wants (for
         * example, the winscope trace directory or the shell's stdout).
         */
        private void forEachViewCaptureWindow(
                @NonNull BiConsumer<String, InputStream> outputtingConsumer) {
            try {
                // This multi-threading prevents ctrl-C command line command aborting from putting
                // the mDumpCallbacks RemoteCallbackList in a bad Broadcast state. We need to wait
                // for it to complete even though it is on a background thread.
                mOnDumpExecutor.submit(() -> {
                    try {
                        for (int i = mDumpCallbacks.beginBroadcast() - 1; i >= 0; i--) {
                            String packageName = (String) mDumpCallbacks.getBroadcastCookie(i);
                            String fileName = WM_TRACE_DIR + packageName + "_" + i + VC_FILE_SUFFIX;

                            try {
                                // Order is important here. OnDump needs to be called before the
                                // BiConsumer accepts & starts blocking on reading the input stream.
                                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                                mDumpCallbacks.getBroadcastItem(i).onDump(pipe[1]);

                                InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(
                                        pipe[0]);
                                outputtingConsumer.accept(fileName, is);
                                is.close();
                            } catch (Exception e) {
                                Log.d(TAG, "failed to pipe view capture data", e);
                            }
                        }
                    } finally {
                        mDumpCallbacks.finishBroadcast();
                    }
                }).get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "background work was interrupted", e);
            }
        }

        @RequiresPermission(READ_FRAME_BUFFER)
        @Override
        public void saveViewCaptureData() {
            int status = checkCallingOrSelfPermissionForPreflight(mContext, READ_FRAME_BUFFER);
            if (PERMISSION_GRANTED == status) {
                forEachViewCaptureWindow(this::dumpViewCaptureDataToWmTrace);
            } else {
                Log.w(TAG, "caller lacks permissions to save view capture data");
            }
        }


        @RequiresPermission(READ_FRAME_BUFFER)
        @Override
        public void registerDumpCallback(@NonNull IDumpCallback cb) {
            int status = checkCallingOrSelfPermissionForPreflight(mContext, READ_FRAME_BUFFER);
            if (PERMISSION_GRANTED == status) {
                String name = mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
                mDumpCallbacks.register(cb, name);
            } else {
                Log.w(TAG, "caller lacks permissions to registerDumpCallback");
            }
        }

        @RequiresPermission(READ_FRAME_BUFFER)
        @Override
        public void unRegisterDumpCallback(@NonNull IDumpCallback cb) {
            int status = checkCallingOrSelfPermissionForPreflight(mContext, READ_FRAME_BUFFER);
            if (PERMISSION_GRANTED == status) {
                mDumpCallbacks.unregister(cb);
            } else {
                Log.w(TAG, "caller lacks permissions to unRegisterDumpCallback");
            }
        }

        @Override
        public void setArchiveCompatibilityOptions(boolean enableIconOverlay,
                boolean enableUnarchivalConfirmation) {
            int callingUid = Binder.getCallingUid();
            Binder.withCleanCallingIdentity(
                    () -> {
                        mAppOpsManager.setUidMode(
                                OP_ARCHIVE_ICON_OVERLAY,
                                callingUid,
                                enableIconOverlay ? MODE_ALLOWED : MODE_IGNORED);
                        mAppOpsManager.setUidMode(
                                OP_UNARCHIVAL_CONFIRMATION,
                                callingUid,
                                enableUnarchivalConfirmation ? MODE_ALLOWED : MODE_IGNORED);
                    });
        }

        /**
         * Checks if user is a profile of or same as listeningUser and the target user is enabled
         * and accessible for caller.
         */
        private boolean isEnabledProfileOf(
                BroadcastCookie cookie, UserHandle user, String debugMsg) {
            if (isHiddenProfile(user)
                    && !canAccessHiddenProfile(cookie.callingUid, cookie.callingPid)) {
                return false;
            }
            return mUserManagerInternal.isProfileAccessible(
                    cookie.user.getIdentifier(), user.getIdentifier(), debugMsg, false);
        }

        /**
         * Returns whether or not the result to the listener should be filtered.
         *
         * @param packageName The package to be accessed by the listener.
         * @param cookie      The listener
         * @param user        The user where the package resides.
         */
        private boolean isPackageVisibleToListener(String packageName, BroadcastCookie cookie,
                UserHandle user) {
            // Do not filter the uninstalled package access since it might break callbacks such as
            // shortcut changes and unavailable packages events.
            return !mPackageManagerInternal.filterAppAccess(packageName, cookie.callingUid,
                    user.getIdentifier(), false /* filterUninstalled */);
        }

        /** Returns whether or not the given appId is in allow list */
        private static boolean isCallingAppIdAllowed(int[] appIdAllowList, @AppIdInt int appId) {
            if (appIdAllowList == null || appId < Process.FIRST_APPLICATION_UID) {
                return true;
            }
            return Arrays.binarySearch(appIdAllowList, appId) > -1;
        }

        private String[] getFilteredPackageNames(String[] packageNames, BroadcastCookie cookie,
                UserHandle user) {
            final List<String> filteredPackageNames = new ArrayList<>();
            for (String packageName : packageNames) {
                if (!isPackageVisibleToListener(packageName, cookie, user)) {
                    continue;
                }
                filteredPackageNames.add(packageName);
            }
            return filteredPackageNames.toArray(new String[filteredPackageNames.size()]);
        }

        private int toShortcutsCacheFlags(int cacheFlags) {
            int ret = 0;
            if (cacheFlags == FLAG_CACHE_NOTIFICATION_SHORTCUTS) {
                ret = ShortcutInfo.FLAG_CACHED_NOTIFICATIONS;
            } else if (cacheFlags == FLAG_CACHE_BUBBLE_SHORTCUTS) {
                ret = ShortcutInfo.FLAG_CACHED_BUBBLES;
            } else if (cacheFlags == FLAG_CACHE_PEOPLE_TILE_SHORTCUTS) {
                ret = ShortcutInfo.FLAG_CACHED_PEOPLE_TILE;
            }
            Preconditions.checkArgumentPositive(ret, "Invalid cache owner");

            return ret;
        }

        @VisibleForTesting
        void postToPackageMonitorHandler(Runnable r) {
            mCallbackHandler.post(r);
        }

        /**
         * Check all installed apps and if a package is installed via Incremental and not fully
         * loaded, register loading progress listener.
         */
        void registerLoadingProgressForIncrementalApps() {
            final List<UserHandle> users = mUm.getUserProfiles();
            if (users == null) {
                return;
            }
            for (UserHandle user : users) {
                mPackageManagerInternal.forEachInstalledPackage(pkg -> {
                    final String packageName = pkg.getPackageName();
                    final IncrementalStatesInfo info =
                            mPackageManagerInternal.getIncrementalStatesInfo(packageName,
                                    Process.myUid(), user.getIdentifier());
                    if (info != null && info.isLoading()) {
                        mPackageManagerInternal.registerInstalledLoadingProgressCallback(
                                packageName, new PackageLoadingProgressCallback(packageName, user),
                                user.getIdentifier());
                    }
                }, user.getIdentifier());
            }
        }

        public static class ShortcutChangeHandler implements LauncherApps.ShortcutChangeCallback {
            private final UserManagerInternal mUserManagerInternal;

            ShortcutChangeHandler(UserManagerInternal userManager) {
                mUserManagerInternal = userManager;
            }

            private final RemoteCallbackList<IShortcutChangeCallback> mCallbacks =
                    new RemoteCallbackList<>();

            public synchronized void addShortcutChangeCallback(IShortcutChangeCallback callback,
                    ShortcutQueryWrapper query, UserHandle user) {
                mCallbacks.unregister(callback);
                mCallbacks.register(callback, new Pair<>(query, user));
            }

            public synchronized void removeShortcutChangeCallback(
                    IShortcutChangeCallback callback) {
                mCallbacks.unregister(callback);
            }

            @Override
            public void onShortcutsAddedOrUpdated(String packageName, List<ShortcutInfo> shortcuts,
                    UserHandle user) {
                onShortcutEvent(packageName, shortcuts, user, false);
            }

            @Override
            public void onShortcutsRemoved(String packageName, List<ShortcutInfo> shortcuts,
                    UserHandle user) {
                onShortcutEvent(packageName, shortcuts, user, true);
            }

            private void onShortcutEvent(String packageName,
                    List<ShortcutInfo> shortcuts, UserHandle user, boolean shortcutsRemoved) {
                int count = mCallbacks.beginBroadcast();

                for (int i = 0; i < count; i++) {
                    final IShortcutChangeCallback callback = mCallbacks.getBroadcastItem(i);
                    final Pair<ShortcutQueryWrapper, UserHandle> cookie =
                            (Pair<ShortcutQueryWrapper, UserHandle>)
                                    mCallbacks.getBroadcastCookie(i);

                    final UserHandle callbackUser = cookie.second;
                    if (callbackUser != null && !hasUserAccess(callbackUser, user)) {
                        // Callback owner does not have access to the shortcuts' user.
                        continue;
                    }

                    // Filter the list by query, if any matches exists, send via callback.
                    List<ShortcutInfo> matchedList = filterShortcutsByQuery(packageName, shortcuts,
                            cookie.first, shortcutsRemoved);
                    if (!CollectionUtils.isEmpty(matchedList)) {
                        try {
                            if (shortcutsRemoved) {
                                callback.onShortcutsRemoved(packageName, matchedList, user);
                            } else {
                                callback.onShortcutsAddedOrUpdated(packageName, matchedList, user);
                            }
                        } catch (RemoteException e) {
                            // The RemoteCallbackList will take care of removing the dead object.
                        }
                    }
                }

                mCallbacks.finishBroadcast();
            }

            public static List<ShortcutInfo> filterShortcutsByQuery(String packageName,
                    List<ShortcutInfo> shortcuts, ShortcutQueryWrapper query,
                    boolean shortcutsRemoved) {
                final long changedSince = query.getChangedSince();
                final String queryPackage = query.getPackage();
                final List<String> shortcutIds = query.getShortcutIds();
                final List<LocusId> locusIds = query.getLocusIds();
                final ComponentName activity = query.getActivity();
                final int flags = query.getQueryFlags();

                if (queryPackage != null && !queryPackage.equals(packageName)) {
                    return null;
                }

                List<ShortcutInfo> matches = new ArrayList<>();

                final boolean matchDynamic = (flags & ShortcutQuery.FLAG_MATCH_DYNAMIC) != 0;
                final boolean matchPinned = (flags & ShortcutQuery.FLAG_MATCH_PINNED) != 0;
                final boolean matchManifest = (flags & ShortcutQuery.FLAG_MATCH_MANIFEST) != 0;
                final boolean matchCached = (flags & ShortcutQuery.FLAG_MATCH_CACHED) != 0;
                final int shortcutFlags = (matchDynamic ? ShortcutInfo.FLAG_DYNAMIC : 0)
                        | (matchPinned ? ShortcutInfo.FLAG_PINNED : 0)
                        | (matchManifest ? ShortcutInfo.FLAG_MANIFEST : 0)
                        | (matchCached ? ShortcutInfo.FLAG_CACHED_ALL : 0);

                for (int i = 0; i < shortcuts.size(); i++) {
                    final ShortcutInfo si = shortcuts.get(i);

                    if (activity != null && !activity.equals(si.getActivity())) {
                        continue;
                    }
                    if (changedSince != 0 && changedSince > si.getLastChangedTimestamp()) {
                        continue;
                    }
                    if (shortcutIds != null && !shortcutIds.contains(si.getId())) {
                        continue;
                    }
                    if (locusIds != null && !locusIds.contains(si.getLocusId())) {
                        continue;
                    }
                    if (shortcutsRemoved || (shortcutFlags & si.getFlags()) != 0) {
                        matches.add(si);
                    }
                }

                return matches;
            }

            private boolean hasUserAccess(UserHandle callbackUser, UserHandle shortcutUser) {
                final int callbackUserId = callbackUser.getIdentifier();
                final int shortcutUserId = shortcutUser.getIdentifier();

                if ((shortcutUser.equals(callbackUser))) return true;
                return mUserManagerInternal.isProfileAccessible(callbackUserId, shortcutUserId,
                        null, false);
            }
        }

        private class PackageRemovedListener extends BroadcastReceiver {

            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
                if (userId == UserHandle.USER_NULL) {
                    Slog.w(TAG, "Intent broadcast does not contain user handle: " + intent);
                    return;
                }
                final String action = intent.getAction();
                // Handle onPackageRemoved.
                if (Intent.ACTION_PACKAGE_REMOVED_INTERNAL.equals(action)) {
                    final String packageName = getPackageName(intent);
                    final int[] appIdAllowList =
                            intent.getIntArrayExtra(Intent.EXTRA_VISIBILITY_ALLOW_LIST);
                    // If {@link #EXTRA_REPLACING} is true, that will be onPackageChanged case.
                    if (packageName != null && !intent.getBooleanExtra(
                            Intent.EXTRA_REPLACING, /* defaultValue= */ false)) {
                        final UserHandle user = new UserHandle(userId);
                        final int n = mListeners.beginBroadcast();
                        try {
                            for (int i = 0; i < n; i++) {
                                final IOnAppsChangedListener listener =
                                        mListeners.getBroadcastItem(i);
                                final BroadcastCookie cookie =
                                        (BroadcastCookie) mListeners.getBroadcastCookie(i);
                                if (!isEnabledProfileOf(cookie, user, "onPackageRemoved")) {
                                    // b/350144057
                                    Slog.d(TAG, "onPackageRemoved: Skipping - profile not enabled"
                                            + " or not accessible for user=" + user
                                            + ", packageName=" + packageName);
                                    continue;
                                }
                                if (!isCallingAppIdAllowed(appIdAllowList, UserHandle.getAppId(
                                        cookie.callingUid))) {
                                    // b/350144057
                                    Slog.d(TAG, "onPackageRemoved: Skipping - appId not allowed"
                                            + " for user=" + user
                                            + ", packageName=" + packageName);
                                    continue;
                                }
                                try {
                                    // b/350144057
                                    Slog.d(TAG, "onPackageRemoved: triggering onPackageRemoved"
                                            + " for user=" + user
                                            + ", packageName=" + packageName);
                                    listener.onPackageRemoved(user, packageName);
                                } catch (RemoteException re) {
                                    Slog.d(TAG, "onPackageRemoved: Callback failed ", re);
                                }
                            }
                        } finally {
                            mListeners.finishBroadcast();
                        }
                    }
                }
            }

            private String getPackageName(Intent intent) {
                final Uri uri = intent.getData();
                final String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
                return pkg;
            }
        }

        private class MyPackageMonitor extends PackageMonitor implements ShortcutChangeListener {

            // TODO Simplify with lambdas.

            @Override
            public void onPackageAdded(String packageName, int uid) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, user, "onPackageAdded")) {
                            // b/350144057
                            Slog.d(TAG, "onPackageAdded: Skipping - profile not enabled"
                                    + " or not accessible for user=" + user
                                    + ", packageName=" + packageName);
                            continue;
                        }
                        if (!isPackageVisibleToListener(packageName, cookie, user)) {
                            // b/350144057
                            Slog.d(TAG, "onPackageAdded: Skipping - package filtered"
                                    + " for user=" + user
                                    + ", packageName=" + packageName);
                            continue;
                        }
                        try {
                            // b/350144057
                            Slog.d(TAG, "onPackageAdded: triggering onPackageAdded"
                                    + " for user=" + user
                                    + ", packageName=" + packageName);
                            listener.onPackageAdded(user, packageName);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "onPackageAdded: Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }
                super.onPackageAdded(packageName, uid);
                mPackageManagerInternal.registerInstalledLoadingProgressCallback(packageName,
                        new PackageLoadingProgressCallback(packageName, user),
                        user.getIdentifier());
            }

            @Override
            public void onPackageModified(String packageName) {
                onPackageChanged(packageName);
                super.onPackageModified(packageName);
            }

            private void onPackageChanged(String packageName) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, user, "onPackageModified")) {
                            continue;
                        }
                        if (!isPackageVisibleToListener(packageName, cookie, user)) {
                            continue;
                        }
                        try {
                            listener.onPackageChanged(user, packageName);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "onPackageChanged: Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }
            }

            @Override
            public void onPackagesAvailable(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, user, "onPackagesAvailable")) {
                            continue;
                        }
                        final String[] filteredPackages =
                                getFilteredPackageNames(packages, cookie, user);
                        // If all packages are filtered, skip notifying listener.
                        if (ArrayUtils.isEmpty(filteredPackages)) {
                            continue;
                        }
                        try {
                            listener.onPackagesAvailable(user, filteredPackages, isReplacing());
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackagesAvailable(packages);
            }

            @Override
            public void onPackagesUnavailable(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, user, "onPackagesUnavailable")) {
                            continue;
                        }
                        final String[] filteredPackages =
                                getFilteredPackageNames(packages, cookie, user);
                        // If all packages are filtered, skip notifying listener.
                        if (ArrayUtils.isEmpty(filteredPackages)) {
                            continue;
                        }
                        try {
                            listener.onPackagesUnavailable(user, filteredPackages, isReplacing());
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackagesUnavailable(packages);
            }

            @Override
            public void onPackagesSuspended(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final ArrayList<Pair<String, Bundle>> packagesWithExtras = new ArrayList<>();
                final ArrayList<String> packagesWithoutExtras = new ArrayList<>();
                for (String pkg : packages) {
                    final Bundle launcherExtras =
                            mPackageManagerInternal.getSuspendedPackageLauncherExtras(pkg,
                                    user.getIdentifier());
                    if (launcherExtras != null) {
                        packagesWithExtras.add(new Pair<>(pkg, launcherExtras));
                    } else {
                        packagesWithoutExtras.add(pkg);
                    }
                }
                final String[] packagesNullExtras = packagesWithoutExtras.toArray(
                        new String[packagesWithoutExtras.size()]);
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, user, "onPackagesSuspended")) {
                            continue;
                        }
                        final String[] filteredPackagesWithoutExtras =
                                getFilteredPackageNames(packagesNullExtras, cookie, user);
                        try {
                            if (!ArrayUtils.isEmpty(filteredPackagesWithoutExtras)) {
                                listener.onPackagesSuspended(user, filteredPackagesWithoutExtras,
                                        /* launcherExtras= */ null);
                            }
                            for (int idx = 0; idx < packagesWithExtras.size(); idx++) {
                                Pair<String, Bundle> packageExtraPair = packagesWithExtras.get(idx);
                                if (!isPackageVisibleToListener(
                                        packageExtraPair.first, cookie, user)) {
                                    continue;
                                }
                                listener.onPackagesSuspended(user,
                                        new String[]{packageExtraPair.first},
                                        packageExtraPair.second);
                            }
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }
            }

            @Override
            public void onPackagesUnsuspended(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, user, "onPackagesUnsuspended")) {
                            continue;
                        }
                        final String[] filteredPackages =
                                getFilteredPackageNames(packages, cookie, user);
                        // If all packages are filtered, skip notifying listener.
                        if (ArrayUtils.isEmpty(filteredPackages)) {
                            continue;
                        }
                        try {
                            listener.onPackagesUnsuspended(user, filteredPackages);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackagesUnsuspended(packages);
            }

            @Override
            public void onShortcutChanged(@NonNull String packageName,
                    @UserIdInt int userId) {
                postToPackageMonitorHandler(() -> onShortcutChangedInner(packageName, userId));
            }

            private void onShortcutChangedInner(@NonNull String packageName,
                    @UserIdInt int userId) {
                final int n = mListeners.beginBroadcast();
                try {
                    final UserHandle user = UserHandle.of(userId);

                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, user, "onShortcutChanged")) {
                            continue;
                        }
                        if (!isPackageVisibleToListener(packageName, cookie, user)) {
                            continue;
                        }
                        final int launcherUserId = cookie.user.getIdentifier();

                        // Make sure the caller has the permission.
                        if (!mShortcutServiceInternal.hasShortcutHostPermission(
                                launcherUserId, cookie.packageName,
                                cookie.callingPid, cookie.callingUid)) {
                            continue;
                        }
                        // Each launcher has a different set of pinned shortcuts, so we need to do a
                        // query in here.
                        // (As of now, only one launcher has the permission at a time, so it's a bit
                        // moot, but we may change the permission model eventually.)
                        final List<ShortcutInfo> list =
                                mShortcutServiceInternal.getShortcuts(launcherUserId,
                                        cookie.packageName,
                                        /* changedSince= */ 0, packageName, /* shortcutIds=*/ null,
                                        /* locusIds=*/ null, /* component= */ null,
                                        ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY
                                        | ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED
                                        , userId, cookie.callingPid, cookie.callingUid);
                        try {
                            listener.onShortcutChanged(user, packageName,
                                    new ParceledListSlice<>(list));
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }

                    }
                } catch (RuntimeException e) {
                    // When the user is locked we get IllegalState, so just catch all.
                    Log.w(TAG, e.getMessage(), e);
                } finally {
                    mListeners.finishBroadcast();
                }
            }

            @Override
            public void onPackageStateChanged(String packageName, int uid) {
                onPackageChanged(packageName);
                super.onPackageStateChanged(packageName, uid);
            }
        }

        class PackageCallbackList<T extends IInterface> extends RemoteCallbackList<T> {
            @Override
            public void onCallbackDied(T callback, Object cookie) {
                checkCallbackCount();
            }
        }

        class PackageLoadingProgressCallback extends
                PackageManagerInternal.InstalledLoadingProgressCallback {
            private final String mPackageName;
            private final UserHandle mUser;

            PackageLoadingProgressCallback(String packageName, UserHandle user) {
                super(mCallbackHandler);
                mPackageName = packageName;
                mUser = user;
            }

            @Override
            public void onLoadingProgressChanged(float progress) {
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie, mUser, "onLoadingProgressChanged")) {
                            continue;
                        }
                        if (!isPackageVisibleToListener(mPackageName, cookie, mUser)) {
                            continue;
                        }
                        try {
                            listener.onPackageLoadingProgressChanged(mUser, mPackageName, progress);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }
            }
        }

        final class LocalService extends LauncherAppsServiceInternal {
            @Override
            public boolean startShortcut(int callerUid, int callerPid, String callingPackage,
                    String packageName, String featureId, String shortcutId, Rect sourceBounds,
                    Bundle startActivityOptions, int targetUserId) {
                return LauncherAppsImpl.this.startShortcutInner(callerUid, callerPid,
                        UserHandle.getUserId(callerUid), callingPackage, packageName, featureId,
                        shortcutId, sourceBounds, startActivityOptions, targetUserId);
            }
        }
    }
}
