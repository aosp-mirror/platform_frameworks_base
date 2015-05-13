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
 * limitations under the License.
 */
package com.android.settingslib.applications;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PermissionsInfo {

    private static final String TAG = "PermissionsInfo";

    private final PackageManager mPm;
    private final ArrayList<PermissionGroup> mGroups = new ArrayList<>();
    private final Map<String, PermissionGroup> mGroupLookup = new ArrayMap<>();
    private final Callback mCallback;
    private final Context mContext;
    // Count of apps that request runtime permissions.
    private int mRuntimePermAppsCt;
    // Count of apps that are granted runtime permissions.
    private int mRuntimePermAppsGrantedCt;

    public PermissionsInfo(Context context, Callback callback) {
        mContext = context;
        mPm = context.getPackageManager();
        mCallback = callback;
        new PermissionsLoader().execute();
    }

    public List<PermissionGroup> getGroups() {
        synchronized (mGroups) {
            return new ArrayList<>(mGroups);
        }
    }

    public int getRuntimePermAppsCount() {
        return mRuntimePermAppsCt;
    }

    public int getRuntimePermAppsGrantedCount() {
        return mRuntimePermAppsGrantedCt;
    }

    private PermissionGroup getOrCreateGroup(String permission) {
        PermissionGroup group = mGroupLookup.get(permission);
        if (group == null) {
            // Some permissions don't have a group, in that case treat them like a group
            // and create their own PermissionGroup (only if they are runtime).
            try {
                PermissionInfo info = mPm.getPermissionInfo(permission, 0);
                if (info.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                    group = new PermissionGroup();
                    // TODO: Add default permission icon.
                    group.icon = info.icon != 0 ? info.loadIcon(mPm) : new ShapeDrawable();
                    group.name = info.name;
                    group.packageName = info.packageName;
                    group.label = info.loadLabel(mPm).toString();
                    mGroups.add(group);
                    mGroupLookup.put(permission, group);
                }
            } catch (NameNotFoundException e) {
                Log.w(TAG, "Unknown permission " + permission, e);
            }
        }
        return group;
    }

    private class PermissionsLoader extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            List<PermissionGroupInfo> groups =
                    mPm.getAllPermissionGroups(PackageManager.GET_META_DATA);
            // Get the groups.
            for (PermissionGroupInfo groupInfo : groups) {
                PermissionGroup group = new PermissionGroup();
                // TODO: Add default permission icon.
                group.icon = groupInfo.icon != 0 ? groupInfo.loadIcon(mPm) : new ShapeDrawable();
                group.name = groupInfo.name;
                group.packageName = groupInfo.packageName;
                group.label = groupInfo.loadLabel(mPm).toString();
                synchronized (mGroups) {
                    mGroups.add(group);
                }
            }
            // Load permissions and which are runtime.
            for (PermissionGroup group : mGroups) {
                try {
                    List<PermissionInfo> permissions =
                            mPm.queryPermissionsByGroup(group.name, 0);
                    for (PermissionInfo info : permissions) {
                        if (info.protectionLevel != PermissionInfo.PROTECTION_DANGEROUS) continue;
                        mGroupLookup.put(info.name, group);
                    }
                } catch (NameNotFoundException e) {
                    Log.w(TAG, "Problem getting permissions", e);
                }
            }
            // Load granted info.
            for (UserHandle user : UserManager.get(mContext).getUserProfiles()) {
                List<PackageInfo> allApps = mPm.getInstalledPackages(
                        PackageManager.GET_PERMISSIONS, user.getIdentifier());
                for (PackageInfo info : allApps) {
                    if (info.applicationInfo.targetSdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1
                            || info.requestedPermissions == null)  {
                        continue;
                    }
                    final int N = info.requestedPermissionsFlags.length;
                    boolean appHasRuntimePerms = false;
                    boolean appGrantedRuntimePerms = false;
                    for (int i = 0; i < N; i++) {
                        boolean granted = (info.requestedPermissionsFlags[i]
                                & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                        PermissionGroup group = getOrCreateGroup(info.requestedPermissions[i]);
                        String key = Integer.toString(info.applicationInfo.uid);
                        if (group != null && !group.possibleApps.contains(key)) {
                            appHasRuntimePerms = true;
                            group.possibleApps.add(key);
                            if (granted) {
                                appGrantedRuntimePerms = true;
                                group.grantedApps.add(key);
                            }
                        }
                    }
                    if (appHasRuntimePerms) {
                        mRuntimePermAppsCt++;
                        if (appGrantedRuntimePerms) {
                            mRuntimePermAppsGrantedCt++;
                        }
                    }
                }
            }
            Collections.sort(mGroups);

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mCallback.onPermissionLoadComplete();
        }
    }

    public static class PermissionGroup implements Comparable<PermissionGroup> {
        public final List<String> possibleApps = new ArrayList<>();
        public final List<String> grantedApps = new ArrayList<>();
        public String name;
        public String packageName;
        public String label;
        public Drawable icon;

        @Override
        public int compareTo(PermissionGroup another) {
            return label.compareTo(another.label);
        }
    }

    public interface Callback {
        void onPermissionLoadComplete();
    }

}
