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

package com.android.server.connectivity;

import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
import static android.content.pm.PackageManager.GET_PERMISSIONS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;

/**
 * A utility class to inform Netd of UID permisisons.
 * Does a mass update at boot and then monitors for app install/remove.
 *
 * @hide
 */
public class PermissionMonitor {
    private static final String TAG = "PermissionMonitor";
    private static final boolean DBG = true;
    private static final boolean SYSTEM = true;
    private static final boolean NETWORK = false;

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final INetworkManagementService mNetd;
    private final BroadcastReceiver mIntentReceiver;

    // Values are User IDs.
    private final Set<Integer> mUsers = new HashSet<Integer>();

    // Keys are App IDs. Values are true for SYSTEM permission and false for NETWORK permission.
    private final Map<Integer, Boolean> mApps = new HashMap<Integer, Boolean>();

    public PermissionMonitor(Context context, INetworkManagementService netd) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mUserManager = UserManager.get(context);
        mNetd = netd;
        mIntentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                int appUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                Uri appData = intent.getData();
                String appName = appData != null ? appData.getSchemeSpecificPart() : null;

                if (Intent.ACTION_USER_ADDED.equals(action)) {
                    onUserAdded(user);
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    onUserRemoved(user);
                } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                    onAppAdded(appName, appUid);
                } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                    onAppRemoved(appUid);
                }
            }
        };
    }

    // Intended to be called only once at startup, after the system is ready. Installs a broadcast
    // receiver to monitor ongoing UID changes, so this shouldn't/needn't be called again.
    public synchronized void startMonitoring() {
        log("Monitoring");

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_ADDED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, intentFilter, null, null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, intentFilter, null, null);

        List<PackageInfo> apps = mPackageManager.getInstalledPackages(GET_PERMISSIONS);
        if (apps == null) {
            loge("No apps");
            return;
        }

        for (PackageInfo app : apps) {
            int uid = app.applicationInfo != null ? app.applicationInfo.uid : -1;
            if (uid < 0) {
                continue;
            }

            boolean isNetwork = hasNetworkPermission(app);
            boolean isSystem = hasSystemPermission(app);

            if (isNetwork || isSystem) {
                Boolean permission = mApps.get(uid);
                // If multiple packages share a UID (cf: android:sharedUserId) and ask for different
                // permissions, don't downgrade (i.e., if it's already SYSTEM, leave it as is).
                if (permission == null || permission == NETWORK) {
                    mApps.put(uid, isSystem);
                }
            }
        }

        List<UserInfo> users = mUserManager.getUsers(true);  // exclude dying users
        if (users != null) {
            for (UserInfo user : users) {
                mUsers.add(user.id);
            }
        }

        log("Users: " + mUsers.size() + ", Apps: " + mApps.size());
        update(mUsers, mApps, true);
    }

    private boolean hasPermission(PackageInfo app, String permission) {
        if (app.requestedPermissions != null) {
            for (String p : app.requestedPermissions) {
                if (permission.equals(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasNetworkPermission(PackageInfo app) {
        return hasPermission(app, CHANGE_NETWORK_STATE);
    }

    private boolean hasSystemPermission(PackageInfo app) {
        int flags = app.applicationInfo != null ? app.applicationInfo.flags : 0;
        if ((flags & FLAG_SYSTEM) != 0 || (flags & FLAG_UPDATED_SYSTEM_APP) != 0) {
            return true;
        }
        return hasPermission(app, CONNECTIVITY_INTERNAL);
    }

    private int[] toIntArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    private void update(Set<Integer> users, Map<Integer, Boolean> apps, boolean add) {
        List<Integer> network = new ArrayList<Integer>();
        List<Integer> system = new ArrayList<Integer>();
        for (Entry<Integer, Boolean> app : apps.entrySet()) {
            List<Integer> list = app.getValue() ? system : network;
            for (int user : users) {
                list.add(UserHandle.getUid(user, app.getKey()));
            }
        }
        try {
            if (add) {
                mNetd.setPermission(CHANGE_NETWORK_STATE, toIntArray(network));
                mNetd.setPermission(CONNECTIVITY_INTERNAL, toIntArray(system));
            } else {
                mNetd.clearPermission(toIntArray(network));
                mNetd.clearPermission(toIntArray(system));
            }
        } catch (RemoteException e) {
            loge("Exception when updating permissions: " + e);
        }
    }

    private synchronized void onUserAdded(int user) {
        if (user < 0) {
            loge("Invalid user in onUserAdded: " + user);
            return;
        }
        mUsers.add(user);

        Set<Integer> users = new HashSet<Integer>();
        users.add(user);
        update(users, mApps, true);
    }

    private synchronized void onUserRemoved(int user) {
        if (user < 0) {
            loge("Invalid user in onUserRemoved: " + user);
            return;
        }
        mUsers.remove(user);

        Set<Integer> users = new HashSet<Integer>();
        users.add(user);
        update(users, mApps, false);
    }

    private synchronized void onAppAdded(String appName, int appUid) {
        if (TextUtils.isEmpty(appName) || appUid < 0) {
            loge("Invalid app in onAppAdded: " + appName + " | " + appUid);
            return;
        }

        try {
            PackageInfo app = mPackageManager.getPackageInfo(appName, GET_PERMISSIONS);
            boolean isNetwork = hasNetworkPermission(app);
            boolean isSystem = hasSystemPermission(app);
            if (isNetwork || isSystem) {
                Boolean permission = mApps.get(appUid);
                // If multiple packages share a UID (cf: android:sharedUserId) and ask for different
                // permissions, don't downgrade (i.e., if it's already SYSTEM, leave it as is).
                if (permission == null || permission == NETWORK) {
                    mApps.put(appUid, isSystem);

                    Map<Integer, Boolean> apps = new HashMap<Integer, Boolean>();
                    apps.put(appUid, isSystem);
                    update(mUsers, apps, true);
                }
            }
        } catch (NameNotFoundException e) {
            loge("NameNotFoundException in onAppAdded: " + e);
        }
    }

    private synchronized void onAppRemoved(int appUid) {
        if (appUid < 0) {
            loge("Invalid app in onAppRemoved: " + appUid);
            return;
        }
        mApps.remove(appUid);

        Map<Integer, Boolean> apps = new HashMap<Integer, Boolean>();
        apps.put(appUid, NETWORK);  // doesn't matter which permission we pick here
        update(mUsers, apps, false);
    }

    private static void log(String s) {
        if (DBG) {
            Log.d(TAG, s);
        }
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
