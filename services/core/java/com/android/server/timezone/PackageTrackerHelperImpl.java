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

package com.android.server.timezone;

import com.android.internal.R;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.util.Slog;

import java.util.List;

/**
 * A single class that implements multiple helper interfaces for use by {@link PackageTracker}.
 */
final class PackageTrackerHelperImpl implements ConfigHelper, PackageManagerHelper {

    private static final String TAG = "PackageTrackerHelperImpl";

    private final Context mContext;
    private final PackageManager mPackageManager;

    PackageTrackerHelperImpl(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    @Override
    public boolean isTrackingEnabled() {
        return mContext.getResources().getBoolean(R.bool.config_timeZoneRulesUpdateTrackingEnabled);
    }

    @Override
    public String getUpdateAppPackageName() {
        return mContext.getResources().getString(R.string.config_timeZoneRulesUpdaterPackage);
    }

    @Override
    public String getDataAppPackageName() {
        Resources resources = mContext.getResources();
        return resources.getString(R.string.config_timeZoneRulesDataPackage);
    }

    @Override
    public int getCheckTimeAllowedMillis() {
        return mContext.getResources().getInteger(
                R.integer.config_timeZoneRulesCheckTimeMillisAllowed);
    }

    @Override
    public int getFailedCheckRetryCount() {
        return mContext.getResources().getInteger(R.integer.config_timeZoneRulesCheckRetryCount);
    }

    @Override
    public long getInstalledPackageVersion(String packageName)
            throws PackageManager.NameNotFoundException {
        int flags = PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName, flags);
        return packageInfo.getLongVersionCode();
    }

    @Override
    public boolean isPrivilegedApp(String packageName) throws PackageManager.NameNotFoundException {
        int flags = PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName, flags);
        return packageInfo.applicationInfo.isPrivilegedApp();
    }

    @Override
    public boolean usesPermission(String packageName, String requiredPermissionName)
            throws PackageManager.NameNotFoundException {
        int flags = PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.GET_PERMISSIONS;
        PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName, flags);
        if (packageInfo.requestedPermissions == null) {
            return false;
        }
        for (String requestedPermission : packageInfo.requestedPermissions) {
            if (requiredPermissionName.equals(requestedPermission)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean contentProviderRegistered(String authority, String requiredPackageName) {
        int flags = PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        ProviderInfo providerInfo = mPackageManager.resolveContentProviderAsUser(
                authority, flags, UserHandle.SYSTEM.getIdentifier());
        if (providerInfo == null) {
            Slog.i(TAG, "contentProviderRegistered: No content provider registered with authority="
                    + authority);
            return false;
        }
        boolean packageMatches =
                requiredPackageName.equals(providerInfo.applicationInfo.packageName);
        if (!packageMatches) {
            Slog.i(TAG, "contentProviderRegistered: App with packageName=" + requiredPackageName
                    + " does not expose the a content provider with authority=" + authority);
            return false;
        }
        return true;
    }

    @Override
    public boolean receiverRegistered(Intent intent, String requiredPermissionName)
            throws PackageManager.NameNotFoundException {

        int flags = PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        List<ResolveInfo> resolveInfo = mPackageManager.queryBroadcastReceiversAsUser(
                intent, flags, UserHandle.SYSTEM);
        if (resolveInfo.size() != 1) {
            Slog.i(TAG, "receiverRegistered: Zero or multiple broadcast receiver registered for"
                    + " intent=" + intent + ", found=" + resolveInfo);
            return false;
        }

        ResolveInfo matched = resolveInfo.get(0);
        boolean requiresPermission = requiredPermissionName.equals(matched.activityInfo.permission);
        if (!requiresPermission) {
            Slog.i(TAG, "receiverRegistered: Broadcast receiver registered for intent="
                    + intent + " must require permission " + requiredPermissionName);
        }
        return requiresPermission;
    }
}
