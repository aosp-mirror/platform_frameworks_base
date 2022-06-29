/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.applications;


import android.app.AppOpsManager;
import android.content.Context;
import android.content.PermissionChecker;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.text.format.DateUtils;
import android.util.IconDrawableFactory;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Retrieval of app ops information for the specified ops.
 */
public class RecentAppOpsAccess {
    @VisibleForTesting
    static final int[] LOCATION_OPS = new int[]{
            AppOpsManager.OP_FINE_LOCATION,
            AppOpsManager.OP_COARSE_LOCATION,
    };
    private static final int[] MICROPHONE_OPS = new int[]{
            AppOpsManager.OP_RECORD_AUDIO,
    };
    private static final int[] CAMERA_OPS = new int[]{
            AppOpsManager.OP_CAMERA,
    };


    private static final String TAG = RecentAppOpsAccess.class.getSimpleName();
    @VisibleForTesting
    public static final String ANDROID_SYSTEM_PACKAGE_NAME = "android";

    // Keep last 24 hours of access app information.
    private static final long RECENT_TIME_INTERVAL_MILLIS = DateUtils.DAY_IN_MILLIS;

    /** The flags for querying ops that are trusted for showing in the UI. */
    public static final int TRUSTED_STATE_FLAGS = AppOpsManager.OP_FLAG_SELF
            | AppOpsManager.OP_FLAG_UNTRUSTED_PROXY
            | AppOpsManager.OP_FLAG_TRUSTED_PROXIED;

    private final PackageManager mPackageManager;
    private final Context mContext;
    private final int[] mOps;
    private final IconDrawableFactory mDrawableFactory;
    private final Clock mClock;

    public RecentAppOpsAccess(Context context, int[] ops) {
        this(context, Clock.systemDefaultZone(), ops);
    }

    @VisibleForTesting
    RecentAppOpsAccess(Context context, Clock clock, int[] ops) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mOps = ops;
        mDrawableFactory = IconDrawableFactory.newInstance(context);
        mClock = clock;
    }

    /**
     * Creates an instance of {@link RecentAppOpsAccess} for location (coarse and fine) access.
     */
    public static RecentAppOpsAccess createForLocation(Context context) {
        return new RecentAppOpsAccess(context, LOCATION_OPS);
    }

    /**
     * Creates an instance of {@link RecentAppOpsAccess} for microphone access.
     */
    public static RecentAppOpsAccess createForMicrophone(Context context) {
        return new RecentAppOpsAccess(context, MICROPHONE_OPS);
    }

    /**
     * Creates an instance of {@link RecentAppOpsAccess} for camera access.
     */
    public static RecentAppOpsAccess createForCamera(Context context) {
        return new RecentAppOpsAccess(context, CAMERA_OPS);
    }

    /**
     * Fills a list of applications which queried for access recently within specified time.
     * Apps are sorted by recency. Apps with more recent accesses are in the front.
     */
    @VisibleForTesting
    public List<Access> getAppList(boolean showSystemApps) {
        // Retrieve a access usage list from AppOps
        AppOpsManager aoManager = mContext.getSystemService(AppOpsManager.class);
        List<AppOpsManager.PackageOps> appOps = aoManager.getPackagesForOps(mOps);

        final int appOpsCount = appOps != null ? appOps.size() : 0;

        // Process the AppOps list and generate a preference list.
        ArrayList<Access> accesses = new ArrayList<>(appOpsCount);
        final long now = mClock.millis();
        final UserManager um = mContext.getSystemService(UserManager.class);
        final List<UserHandle> profiles = um.getUserProfiles();

        for (int i = 0; i < appOpsCount; ++i) {
            AppOpsManager.PackageOps ops = appOps.get(i);
            String packageName = ops.getPackageName();
            int uid = ops.getUid();
            UserHandle user = UserHandle.getUserHandleForUid(uid);

            // Don't show apps belonging to background users except managed users.
            if (!profiles.contains(user)) {
                continue;
            }

            // Don't show apps that do not have user sensitive location permissions
            boolean showApp = true;
            if (!showSystemApps) {
                for (int op : mOps) {
                    final String permission = AppOpsManager.opToPermission(op);
                    final int permissionFlags = mPackageManager.getPermissionFlags(permission,
                            packageName,
                            user);
                    if (PermissionChecker.checkPermissionForPreflight(mContext, permission,
                            PermissionChecker.PID_UNKNOWN, uid, packageName)
                            == PermissionChecker.PERMISSION_GRANTED) {
                        if ((permissionFlags
                                & PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED)
                                == 0) {
                            showApp = false;
                            break;
                        }
                    } else {
                        if ((permissionFlags
                                & PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED) == 0) {
                            showApp = false;
                            break;
                        }
                    }
                }
            }
            if (showApp && PermissionManager.shouldShowPackageForIndicatorCached(mContext,
                    packageName)) {
                Access access = getAccessFromOps(now, ops);
                if (access != null) {
                    accesses.add(access);
                }
            }
        }
        return accesses;
    }

    /**
     * Gets a list of apps that accessed the app op recently, sorting by recency.
     *
     * @param showSystemApps whether includes system apps in the list.
     * @return the list of apps that recently accessed the app op.
     */
    public List<Access> getAppListSorted(boolean showSystemApps) {
        List<Access> accesses = getAppList(showSystemApps);
        // Sort the list of Access by recency. Most recent accesses first.
        Collections.sort(accesses, Collections.reverseOrder(new Comparator<Access>() {
            @Override
            public int compare(Access access1, Access access2) {
                return Long.compare(access1.accessFinishTime, access2.accessFinishTime);
            }
        }));
        return accesses;
    }

    /**
     * Creates a Access entry for the given PackageOps.
     *
     * This method examines the time interval of the PackageOps first. If the PackageOps is older
     * than the designated interval, this method ignores the PackageOps object and returns null.
     * When the PackageOps is fresh enough, this method returns a Access object for the package
     */
    private Access getAccessFromOps(long now,
            AppOpsManager.PackageOps ops) {
        String packageName = ops.getPackageName();
        List<AppOpsManager.OpEntry> entries = ops.getOps();
        long accessFinishTime = 0L;
        // Earliest time for a access to end and still be shown in list.
        long recentAccessCutoffTime = now - RECENT_TIME_INTERVAL_MILLIS;
        // Compute the most recent access time from all op entries.
        for (AppOpsManager.OpEntry entry : entries) {
            long lastAccessTime = entry.getLastAccessTime(TRUSTED_STATE_FLAGS);
            if (lastAccessTime > accessFinishTime) {
                accessFinishTime = lastAccessTime;
            }
        }
        // Bail out if the entry is out of date.
        if (accessFinishTime < recentAccessCutoffTime) {
            return null;
        }

        // The package is fresh enough, continue.
        int uid = ops.getUid();
        int userId = UserHandle.getUserId(uid);

        Access access = null;
        try {
            ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.GET_META_DATA, userId);
            if (appInfo == null) {
                Log.w(TAG, "Null application info retrieved for package " + packageName
                        + ", userId " + userId);
                return null;
            }

            final UserHandle userHandle = new UserHandle(userId);
            Drawable icon = mDrawableFactory.getBadgedIcon(appInfo, userId);
            CharSequence appLabel = mPackageManager.getApplicationLabel(appInfo);
            CharSequence badgedAppLabel = mPackageManager.getUserBadgedLabel(appLabel, userHandle);
            if (appLabel.toString().contentEquals(badgedAppLabel)) {
                // If badged label is not different from original then no need for it as
                // a separate content description.
                badgedAppLabel = null;
            }
            access = new Access(packageName, userHandle, icon, appLabel, badgedAppLabel,
                    accessFinishTime);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "package name not found for " + packageName + ", userId " + userId);
        }
        return access;
    }

    /**
     * Information about when an app last accessed a particular app op.
     */
    public static class Access {
        public final String packageName;
        public final UserHandle userHandle;
        public final Drawable icon;
        public final CharSequence label;
        public final CharSequence contentDescription;
        public final long accessFinishTime;

        public Access(String packageName, UserHandle userHandle, Drawable icon,
                CharSequence label, CharSequence contentDescription,
                long accessFinishTime) {
            this.packageName = packageName;
            this.userHandle = userHandle;
            this.icon = icon;
            this.label = label;
            this.contentDescription = contentDescription;
            this.accessFinishTime = accessFinishTime;
        }
    }
}
