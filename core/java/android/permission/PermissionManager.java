/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.permission;

import android.Manifest;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.Immutable;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * System level service for accessing the permission capabilities of the platform.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.PERMISSION_SERVICE)
public final class PermissionManager {
    private static final String LOG_TAG = PermissionManager.class.getSimpleName();

    /**
     * {@link android.content.pm.PackageParser} needs access without having a {@link Context}.
     *
     * @hide
     */
    public static final ArrayList<SplitPermissionInfo> SPLIT_PERMISSIONS =
            SystemConfig.getInstance().getSplitPermissions();

    private final @NonNull Context mContext;

    private final IPackageManager mPackageManager;

    /** Permission denials added via {@link addPermissionDenial} */
    private static final ThreadLocal<List<String>> sPermissionDenialHints = new ThreadLocal<>();

    /**
     * Report a hint that might explain why a permission check returned
     * {@link PackageManager#PERMISSION_DENIED}.
     *
     * <p>Hints are only collected if enabled via {@link collectPermissionDenialHints} or
     * when a non-null value was passed to {@link resetPermissionDenialHints}
     *
     * @param hint A description of the reason
     *
     * @hide
     */
    public static void addPermissionDenialHint(@NonNull String hint) {
        List<String> hints = sPermissionDenialHints.get();
        if (hints == null) {
            return;
        }

        hints.add(hint);
    }

    /**
     * @return hints added via {@link #addPermissionDenialHint(String)} on this thread before.
     *
     * @hide
     */
    public static @Nullable List<String> getPermissionDenialHints() {
        if (Build.IS_USER) {
            return null;
        }

        return sPermissionDenialHints.get();
    }

    /**
     * Reset the permission denial hints for this thread.
     *
     * @param initial The initial values. If not null, enabled collection on this thread.
     *
     * @return the previously collected hints
     *
     * @hide
     */
    public static @Nullable List<String> resetPermissionDenialHints(
            @Nullable List<String> initial) {
        List<String> prev = getPermissionDenialHints();
        if (initial == null) {
            sPermissionDenialHints.remove();
        } else {
            sPermissionDenialHints.set(initial);
        }
        return prev;
    }

    /**
     * Enable permission denial hint collection if package is in
     * {@link Settings.Secure.DEBUG_PACKAGE_PERMISSION_CHECK}
     *
     * @param context A context to use
     * @param uid The uid the permission check is for.
     *
     * @return the previously collected hints
     *
     * @hide
     */
    public static @Nullable List<String> collectPermissionDenialHints(@NonNull Context context,
            int uid) {
        List<String> prev = getPermissionDenialHints();

        if (Build.IS_USER) {
            return prev;
        }

        ContentResolver cr = context.getContentResolver();
        if (cr == null) {
            return prev;
        }

        String debugSetting;
        try {
            debugSetting = Settings.Secure.getString(cr,
                    Settings.Secure.DEBUG_PACKAGE_PERMISSION_CHECK);
        } catch (IllegalStateException e) {
            Log.e(LOG_TAG, "Cannot access settings", e);
            return prev;
        }
        if (debugSetting == null) {
            return prev;
        }
        String[] debugPkgs = debugSetting.split(",");

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return prev;
        }

        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) {
            return prev;
        }

        for (String pkg : packages) {
            if (ArrayUtils.contains(debugPkgs, pkg)) {
                sPermissionDenialHints.set(new ArrayList<>(0));
                break;
            }
        }

        return prev;
    }

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @hide
     */
    public PermissionManager(@NonNull Context context, IPackageManager packageManager) {
        mContext = context;
        mPackageManager = packageManager;
    }

    /**
     * Gets the version of the runtime permission database.
     *
     * @return The database version.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
    public @IntRange(from = 0) int getRuntimePermissionsVersion() {
        try {
            return mPackageManager.getRuntimePermissionsVersion(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the version of the runtime permission database.
     *
     * @param version The new version.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
    public void setRuntimePermissionsVersion(@IntRange(from = 0) int version) {
        try {
            mPackageManager.setRuntimePermissionsVersion(version, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get set of permissions that have been split into more granular or dependent permissions.
     *
     * <p>E.g. before {@link android.os.Build.VERSION_CODES#Q} an app that was granted
     * {@link Manifest.permission#ACCESS_COARSE_LOCATION} could access he location while it was in
     * foreground and background. On platforms after {@link android.os.Build.VERSION_CODES#Q}
     * the location permission only grants location access while the app is in foreground. This
     * would break apps that target before {@link android.os.Build.VERSION_CODES#Q}. Hence whenever
     * such an old app asks for a location permission (i.e. the
     * {@link SplitPermissionInfo#getSplitPermission()}), then the
     * {@link Manifest.permission#ACCESS_BACKGROUND_LOCATION} permission (inside
     * {@link SplitPermissionInfo#getNewPermissions}) is added.
     *
     * <p>Note: Regular apps do not have to worry about this. The platform and permission controller
     * automatically add the new permissions where needed.
     *
     * @return All permissions that are split.
     */
    public @NonNull List<SplitPermissionInfo> getSplitPermissions() {
        return SPLIT_PERMISSIONS;
    }

    /**
     * A permission that was added in a previous API level might have split into several
     * permissions. This object describes one such split.
     */
    @Immutable
    public static final class SplitPermissionInfo {
        private final @NonNull String mSplitPerm;
        private final @NonNull List<String> mNewPerms;
        private final int mTargetSdk;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SplitPermissionInfo that = (SplitPermissionInfo) o;
            return mTargetSdk == that.mTargetSdk
                    && Objects.equals(mSplitPerm, that.mSplitPerm);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSplitPerm, mTargetSdk);
        }

        /**
         * Get the permission that is split.
         */
        public @NonNull String getSplitPermission() {
            return mSplitPerm;
        }

        /**
         * Get the permissions that are added.
         */
        public @NonNull List<String> getNewPermissions() {
            return mNewPerms;
        }

        /**
         * Get the target API level when the permission was split.
         */
        public int getTargetSdk() {
            return mTargetSdk;
        }

        /**
         * Constructs a split permission.
         *
         * @param splitPerm old permission that will be split
         * @param newPerms list of new permissions that {@code rootPerm} will be split into
         * @param targetSdk apps targetting SDK versions below this will have {@code rootPerm}
         * split into {@code newPerms}
         * @hide
         */
        public SplitPermissionInfo(@NonNull String splitPerm, @NonNull List<String> newPerms,
                int targetSdk) {
            mSplitPerm = splitPerm;
            mNewPerms = newPerms;
            mTargetSdk = targetSdk;
        }
    }
}
