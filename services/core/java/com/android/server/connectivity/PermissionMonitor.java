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
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.net.INetd;
import android.net.UidRange;
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.os.UserManager;
import android.system.OsConstants;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
    protected static final Boolean SYSTEM = Boolean.TRUE;
    protected static final Boolean NETWORK = Boolean.FALSE;
    private static final int VERSION_Q = Build.VERSION_CODES.Q;

    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final INetd mNetd;

    // Values are User IDs.
    @GuardedBy("this")
    private final Set<Integer> mUsers = new HashSet<>();

    // Keys are app uids. Values are true for SYSTEM permission and false for NETWORK permission.
    @GuardedBy("this")
    private final Map<Integer, Boolean> mApps = new HashMap<>();

    // Keys are active non-bypassable and fully-routed VPN's interface name, Values are uid ranges
    // for apps under the VPN
    @GuardedBy("this")
    private final Map<String, Set<UidRange>> mVpnUidRanges = new HashMap<>();

    // A set of appIds for apps across all users on the device. We track appIds instead of uids
    // directly to reduce its size and also eliminate the need to update this set when user is
    // added/removed.
    @GuardedBy("this")
    private final Set<Integer> mAllApps = new HashSet<>();

    private class PackageListObserver implements PackageManagerInternal.PackageListObserver {

        private int getPermissionForUid(int uid) {
            int permission = 0;
            // Check all the packages for this UID. The UID has the permission if any of the
            // packages in it has the permission.
            String[] packages = mPackageManager.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                for (String name : packages) {
                    final PackageInfo app = getPackageInfo(name);
                    if (app != null && app.requestedPermissions != null) {
                        permission |= getNetdPermissionMask(app.requestedPermissions,
                              app.requestedPermissionsFlags);
                    }
                }
            } else {
                // The last package of this uid is removed from device. Clean the package up.
                permission = INetd.PERMISSION_UNINSTALLED;
            }
            return permission;
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            sendPackagePermissionsForUid(uid, getPermissionForUid(uid));
        }

        @Override
        public void onPackageChanged(@NonNull String packageName, int uid) {
            sendPackagePermissionsForUid(uid, getPermissionForUid(uid));
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            sendPackagePermissionsForUid(uid, getPermissionForUid(uid));
        }
    }

    public PermissionMonitor(Context context, INetd netd) {
        mPackageManager = context.getPackageManager();
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mNetd = netd;
    }

    // Intended to be called only once at startup, after the system is ready. Installs a broadcast
    // receiver to monitor ongoing UID changes, so this shouldn't/needn't be called again.
    public synchronized void startMonitoring() {
        log("Monitoring");

        PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        if (pmi != null) {
            pmi.getPackageList(new PackageListObserver());
        } else {
            loge("failed to get the PackageManagerInternal service");
        }
        List<PackageInfo> apps = mPackageManager.getInstalledPackages(GET_PERMISSIONS
                | MATCH_ANY_USER);
        if (apps == null) {
            loge("No apps");
            return;
        }

        SparseIntArray netdPermsUids = new SparseIntArray();

        for (PackageInfo app : apps) {
            int uid = app.applicationInfo != null ? app.applicationInfo.uid : INVALID_UID;
            if (uid < 0) {
                continue;
            }
            mAllApps.add(UserHandle.getAppId(uid));

            boolean isNetwork = hasNetworkPermission(app);
            boolean hasRestrictedPermission = hasRestrictedNetworkPermission(app);

            if (isNetwork || hasRestrictedPermission) {
                Boolean permission = mApps.get(uid);
                // If multiple packages share a UID (cf: android:sharedUserId) and ask for different
                // permissions, don't downgrade (i.e., if it's already SYSTEM, leave it as is).
                if (permission == null || permission == NETWORK) {
                    mApps.put(uid, hasRestrictedPermission);
                }
            }

            //TODO: unify the management of the permissions into one codepath.
            int otherNetdPerms = getNetdPermissionMask(app.requestedPermissions,
                    app.requestedPermissionsFlags);
            netdPermsUids.put(uid, netdPermsUids.get(uid) | otherNetdPerms);
        }

        List<UserInfo> users = mUserManager.getUsers(true);  // exclude dying users
        if (users != null) {
            for (UserInfo user : users) {
                mUsers.add(user.id);
            }
        }

        final SparseArray<ArraySet<String>> systemPermission =
                SystemConfig.getInstance().getSystemPermissions();
        for (int i = 0; i < systemPermission.size(); i++) {
            ArraySet<String> perms = systemPermission.valueAt(i);
            int uid = systemPermission.keyAt(i);
            int netdPermission = 0;
            // Get the uids of native services that have UPDATE_DEVICE_STATS or INTERNET permission.
            if (perms != null) {
                netdPermission |= perms.contains(UPDATE_DEVICE_STATS)
                        ? INetd.PERMISSION_UPDATE_DEVICE_STATS : 0;
                netdPermission |= perms.contains(INTERNET)
                        ? INetd.PERMISSION_INTERNET : 0;
            }
            netdPermsUids.put(uid, netdPermsUids.get(uid) | netdPermission);
        }
        log("Users: " + mUsers.size() + ", Apps: " + mApps.size());
        update(mUsers, mApps, true);
        sendPackagePermissionsToNetd(netdPermsUids);
    }

    @VisibleForTesting
    static boolean isVendorApp(@NonNull ApplicationInfo appInfo) {
        return appInfo.isVendor() || appInfo.isOem() || appInfo.isProduct();
    }

    @VisibleForTesting
    protected int getDeviceFirstSdkInt() {
        return Build.VERSION.FIRST_SDK_INT;
    }

    @VisibleForTesting
    boolean hasPermission(@NonNull final PackageInfo app, @NonNull final String permission) {
        if (app.requestedPermissions == null || app.requestedPermissionsFlags == null) {
            return false;
        }
        final int index = ArrayUtils.indexOf(app.requestedPermissions, permission);
        if (index < 0 || index >= app.requestedPermissionsFlags.length) return false;
        return (app.requestedPermissionsFlags[index] & REQUESTED_PERMISSION_GRANTED) != 0;
    }

    @VisibleForTesting
    boolean hasNetworkPermission(@NonNull final PackageInfo app) {
        return hasPermission(app, CHANGE_NETWORK_STATE);
    }

    @VisibleForTesting
    boolean hasRestrictedNetworkPermission(@NonNull final PackageInfo app) {
        // TODO : remove this check in the future(b/31479477). All apps should just
        // request the appropriate permission for their use case since android Q.
        if (app.applicationInfo != null) {
            // Backward compatibility for b/114245686, on devices that launched before Q daemons
            // and apps running as the system UID are exempted from this check.
            if (app.applicationInfo.uid == SYSTEM_UID && getDeviceFirstSdkInt() < VERSION_Q) {
                return true;
            }

            if (app.applicationInfo.targetSdkVersion < VERSION_Q
                    && isVendorApp(app.applicationInfo)) {
                return true;
            }
        }
        return hasPermission(app, CONNECTIVITY_INTERNAL)
                || hasPermission(app, NETWORK_STACK)
                || hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS);
    }

    /** Returns whether the given uid has using background network permission. */
    public synchronized boolean hasUseBackgroundNetworksPermission(final int uid) {
        // Apps with any of the CHANGE_NETWORK_STATE, NETWORK_STACK, CONNECTIVITY_INTERNAL or
        // CONNECTIVITY_USE_RESTRICTED_NETWORKS permission has the permission to use background
        // networks. mApps contains the result of checks for both hasNetworkPermission and
        // hasRestrictedNetworkPermission. If uid is in the mApps list that means uid has one of
        // permissions at least.
        return mApps.containsKey(uid);
    }

    private int[] toIntArray(Collection<Integer> list) {
        int[] array = new int[list.size()];
        int i = 0;
        for (Integer item : list) {
            array[i++] = item;
        }
        return array;
    }

    private void update(Set<Integer> users, Map<Integer, Boolean> apps, boolean add) {
        List<Integer> network = new ArrayList<>();
        List<Integer> system = new ArrayList<>();
        for (Entry<Integer, Boolean> app : apps.entrySet()) {
            List<Integer> list = app.getValue() ? system : network;
            for (int user : users) {
                list.add(UserHandle.getUid(user, app.getKey()));
            }
        }
        try {
            if (add) {
                mNetd.networkSetPermissionForUser(INetd.PERMISSION_NETWORK, toIntArray(network));
                mNetd.networkSetPermissionForUser(INetd.PERMISSION_SYSTEM, toIntArray(system));
            } else {
                mNetd.networkClearPermissionForUser(toIntArray(network));
                mNetd.networkClearPermissionForUser(toIntArray(system));
            }
        } catch (RemoteException e) {
            loge("Exception when updating permissions: " + e);
        }
    }

    /**
     * Called when a user is added. See {link #ACTION_USER_ADDED}.
     *
     * @param user The integer userHandle of the added user. See {@link #EXTRA_USER_HANDLE}.
     *
     * @hide
     */
    public synchronized void onUserAdded(int user) {
        if (user < 0) {
            loge("Invalid user in onUserAdded: " + user);
            return;
        }
        mUsers.add(user);

        Set<Integer> users = new HashSet<>();
        users.add(user);
        update(users, mApps, true);
    }

    /**
     * Called when an user is removed. See {link #ACTION_USER_REMOVED}.
     *
     * @param user The integer userHandle of the removed user. See {@link #EXTRA_USER_HANDLE}.
     *
     * @hide
     */
    public synchronized void onUserRemoved(int user) {
        if (user < 0) {
            loge("Invalid user in onUserRemoved: " + user);
            return;
        }
        mUsers.remove(user);

        Set<Integer> users = new HashSet<>();
        users.add(user);
        update(users, mApps, false);
    }

    @VisibleForTesting
    protected Boolean highestPermissionForUid(Boolean currentPermission, String name) {
        if (currentPermission == SYSTEM) {
            return currentPermission;
        }
        try {
            final PackageInfo app = mPackageManager.getPackageInfo(name, GET_PERMISSIONS);
            final boolean isNetwork = hasNetworkPermission(app);
            final boolean hasRestrictedPermission = hasRestrictedNetworkPermission(app);
            if (isNetwork || hasRestrictedPermission) {
                currentPermission = hasRestrictedPermission;
            }
        } catch (NameNotFoundException e) {
            // App not found.
            loge("NameNotFoundException " + name);
        }
        return currentPermission;
    }

    /**
     * Called when a package is added. See {link #ACTION_PACKAGE_ADDED}.
     *
     * @param packageName The name of the new package.
     * @param uid The uid of the new package.
     *
     * @hide
     */
    public synchronized void onPackageAdded(String packageName, int uid) {
        // If multiple packages share a UID (cf: android:sharedUserId) and ask for different
        // permissions, don't downgrade (i.e., if it's already SYSTEM, leave it as is).
        final Boolean permission = highestPermissionForUid(mApps.get(uid), packageName);
        if (permission != mApps.get(uid)) {
            mApps.put(uid, permission);

            Map<Integer, Boolean> apps = new HashMap<>();
            apps.put(uid, permission);
            update(mUsers, apps, true);
        }

        // If the newly-installed package falls within some VPN's uid range, update Netd with it.
        // This needs to happen after the mApps update above, since removeBypassingUids() depends
        // on mApps to check if the package can bypass VPN.
        for (Map.Entry<String, Set<UidRange>> vpn : mVpnUidRanges.entrySet()) {
            if (UidRange.containsUid(vpn.getValue(), uid)) {
                final Set<Integer> changedUids = new HashSet<>();
                changedUids.add(uid);
                removeBypassingUids(changedUids, /* vpnAppUid */ -1);
                updateVpnUids(vpn.getKey(), changedUids, true);
            }
        }
        mAllApps.add(UserHandle.getAppId(uid));
    }

    /**
     * Called when a package is removed. See {link #ACTION_PACKAGE_REMOVED}.
     *
     * @param uid containing the integer uid previously assigned to the package.
     *
     * @hide
     */
    public synchronized void onPackageRemoved(int uid) {
        // If the newly-removed package falls within some VPN's uid range, update Netd with it.
        // This needs to happen before the mApps update below, since removeBypassingUids() depends
        // on mApps to check if the package can bypass VPN.
        for (Map.Entry<String, Set<UidRange>> vpn : mVpnUidRanges.entrySet()) {
            if (UidRange.containsUid(vpn.getValue(), uid)) {
                final Set<Integer> changedUids = new HashSet<>();
                changedUids.add(uid);
                removeBypassingUids(changedUids, /* vpnAppUid */ -1);
                updateVpnUids(vpn.getKey(), changedUids, false);
            }
        }
        // If the package has been removed from all users on the device, clear it form mAllApps.
        if (mPackageManager.getNameForUid(uid) == null) {
            mAllApps.remove(UserHandle.getAppId(uid));
        }

        Map<Integer, Boolean> apps = new HashMap<>();
        Boolean permission = null;
        String[] packages = mPackageManager.getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            for (String name : packages) {
                permission = highestPermissionForUid(permission, name);
                if (permission == SYSTEM) {
                    // An app with this UID still has the SYSTEM permission.
                    // Therefore, this UID must already have the SYSTEM permission.
                    // Nothing to do.
                    return;
                }
            }
        }
        if (permission == mApps.get(uid)) {
            // The permissions of this UID have not changed. Nothing to do.
            return;
        } else if (permission != null) {
            mApps.put(uid, permission);
            apps.put(uid, permission);
            update(mUsers, apps, true);
        } else {
            mApps.remove(uid);
            apps.put(uid, NETWORK);  // doesn't matter which permission we pick here
            update(mUsers, apps, false);
        }
    }

    private static int getNetdPermissionMask(String[] requestedPermissions,
                                             int[] requestedPermissionsFlags) {
        int permissions = 0;
        if (requestedPermissions == null || requestedPermissionsFlags == null) return permissions;
        for (int i = 0; i < requestedPermissions.length; i++) {
            if (requestedPermissions[i].equals(INTERNET)
                    && ((requestedPermissionsFlags[i] & REQUESTED_PERMISSION_GRANTED) != 0)) {
                permissions |= INetd.PERMISSION_INTERNET;
            }
            if (requestedPermissions[i].equals(UPDATE_DEVICE_STATS)
                    && ((requestedPermissionsFlags[i] & REQUESTED_PERMISSION_GRANTED) != 0)) {
                permissions |= INetd.PERMISSION_UPDATE_DEVICE_STATS;
            }
        }
        return permissions;
    }

    private PackageInfo getPackageInfo(String packageName) {
        try {
            PackageInfo app = mPackageManager.getPackageInfo(packageName, GET_PERMISSIONS
                    | MATCH_ANY_USER);
            return app;
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Called when a new set of UID ranges are added to an active VPN network
     *
     * @param iface The active VPN network's interface name
     * @param rangesToAdd The new UID ranges to be added to the network
     * @param vpnAppUid The uid of the VPN app
     */
    public synchronized void onVpnUidRangesAdded(@NonNull String iface, Set<UidRange> rangesToAdd,
            int vpnAppUid) {
        // Calculate the list of new app uids under the VPN due to the new UID ranges and update
        // Netd about them. Because mAllApps only contains appIds instead of uids, the result might
        // be an overestimation if an app is not installed on the user on which the VPN is running,
        // but that's safe.
        final Set<Integer> changedUids = intersectUids(rangesToAdd, mAllApps);
        removeBypassingUids(changedUids, vpnAppUid);
        updateVpnUids(iface, changedUids, true);
        if (mVpnUidRanges.containsKey(iface)) {
            mVpnUidRanges.get(iface).addAll(rangesToAdd);
        } else {
            mVpnUidRanges.put(iface, new HashSet<UidRange>(rangesToAdd));
        }
    }

    /**
     * Called when a set of UID ranges are removed from an active VPN network
     *
     * @param iface The VPN network's interface name
     * @param rangesToRemove Existing UID ranges to be removed from the VPN network
     * @param vpnAppUid The uid of the VPN app
     */
    public synchronized void onVpnUidRangesRemoved(@NonNull String iface,
            Set<UidRange> rangesToRemove, int vpnAppUid) {
        // Calculate the list of app uids that are no longer under the VPN due to the removed UID
        // ranges and update Netd about them.
        final Set<Integer> changedUids = intersectUids(rangesToRemove, mAllApps);
        removeBypassingUids(changedUids, vpnAppUid);
        updateVpnUids(iface, changedUids, false);
        Set<UidRange> existingRanges = mVpnUidRanges.getOrDefault(iface, null);
        if (existingRanges == null) {
            loge("Attempt to remove unknown vpn uid Range iface = " + iface);
            return;
        }
        existingRanges.removeAll(rangesToRemove);
        if (existingRanges.size() == 0) {
            mVpnUidRanges.remove(iface);
        }
    }

    /**
     * Compute the intersection of a set of UidRanges and appIds. Returns a set of uids
     * that satisfies:
     *   1. falls into one of the UidRange
     *   2. matches one of the appIds
     */
    private Set<Integer> intersectUids(Set<UidRange> ranges, Set<Integer> appIds) {
        Set<Integer> result = new HashSet<>();
        for (UidRange range : ranges) {
            for (int userId = range.getStartUser(); userId <= range.getEndUser(); userId++) {
                for (int appId : appIds) {
                    final int uid = UserHandle.getUid(userId, appId);
                    if (range.contains(uid)) {
                        result.add(uid);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Remove all apps which can elect to bypass the VPN from the list of uids
     *
     * An app can elect to bypass the VPN if it hold SYSTEM permission, or if its the active VPN
     * app itself.
     *
     * @param uids The list of uids to operate on
     * @param vpnAppUid The uid of the VPN app
     */
    private void removeBypassingUids(Set<Integer> uids, int vpnAppUid) {
        uids.remove(vpnAppUid);
        uids.removeIf(uid -> mApps.getOrDefault(uid, NETWORK) == SYSTEM);
    }

    /**
     * Update netd about the list of uids that are under an active VPN connection which they cannot
     * bypass.
     *
     * This is to instruct netd to set up appropriate filtering rules for these uids, such that they
     * can only receive ingress packets from the VPN's tunnel interface (and loopback).
     *
     * @param iface the interface name of the active VPN connection
     * @param add {@code true} if the uids are to be added to the interface, {@code false} if they
     *        are to be removed from the interface.
     */
    private void updateVpnUids(String iface, Set<Integer> uids, boolean add) {
        if (uids.size() == 0) {
            return;
        }
        try {
            if (add) {
                mNetd.firewallAddUidInterfaceRules(iface, toIntArray(uids));
            } else {
                mNetd.firewallRemoveUidInterfaceRules(toIntArray(uids));
            }
        } catch (ServiceSpecificException e) {
            // Silently ignore exception when device does not support eBPF, otherwise just log
            // the exception and do not crash
            if (e.errorCode != OsConstants.EOPNOTSUPP) {
                loge("Exception when updating permissions: ", e);
            }
        } catch (RemoteException e) {
            loge("Exception when updating permissions: ", e);
        }
    }

    /**
     * Called by PackageListObserver when a package is installed/uninstalled. Send the updated
     * permission information to netd.
     *
     * @param uid the app uid of the package installed
     * @param permissions the permissions the app requested and netd cares about.
     *
     * @hide
     */
    @VisibleForTesting
    void sendPackagePermissionsForUid(int uid, int permissions) {
        SparseIntArray netdPermissionsAppIds = new SparseIntArray();
        netdPermissionsAppIds.put(uid, permissions);
        sendPackagePermissionsToNetd(netdPermissionsAppIds);
    }

    /**
     * Called by packageManagerService to send IPC to netd. Grant or revoke the INTERNET
     * and/or UPDATE_DEVICE_STATS permission of the uids in array.
     *
     * @param netdPermissionsAppIds integer pairs of uids and the permission granted to it. If the
     * permission is 0, revoke all permissions of that uid.
     *
     * @hide
     */
    @VisibleForTesting
    void sendPackagePermissionsToNetd(SparseIntArray netdPermissionsAppIds) {
        if (mNetd == null) {
            Log.e(TAG, "Failed to get the netd service");
            return;
        }
        ArrayList<Integer> allPermissionAppIds = new ArrayList<>();
        ArrayList<Integer> internetPermissionAppIds = new ArrayList<>();
        ArrayList<Integer> updateStatsPermissionAppIds = new ArrayList<>();
        ArrayList<Integer> noPermissionAppIds = new ArrayList<>();
        ArrayList<Integer> uninstalledAppIds = new ArrayList<>();
        for (int i = 0; i < netdPermissionsAppIds.size(); i++) {
            int permissions = netdPermissionsAppIds.valueAt(i);
            switch(permissions) {
                case (INetd.PERMISSION_INTERNET | INetd.PERMISSION_UPDATE_DEVICE_STATS):
                    allPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case INetd.PERMISSION_INTERNET:
                    internetPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case INetd.PERMISSION_UPDATE_DEVICE_STATS:
                    updateStatsPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case INetd.PERMISSION_NONE:
                    noPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case INetd.PERMISSION_UNINSTALLED:
                    uninstalledAppIds.add(netdPermissionsAppIds.keyAt(i));
                default:
                    Log.e(TAG, "unknown permission type: " + permissions + "for uid: "
                            + netdPermissionsAppIds.keyAt(i));
            }
        }
        try {
            // TODO: add a lock inside netd to protect IPC trafficSetNetPermForUids()
            if (allPermissionAppIds.size() != 0) {
                mNetd.trafficSetNetPermForUids(
                        INetd.PERMISSION_INTERNET | INetd.PERMISSION_UPDATE_DEVICE_STATS,
                        ArrayUtils.convertToIntArray(allPermissionAppIds));
            }
            if (internetPermissionAppIds.size() != 0) {
                mNetd.trafficSetNetPermForUids(INetd.PERMISSION_INTERNET,
                        ArrayUtils.convertToIntArray(internetPermissionAppIds));
            }
            if (updateStatsPermissionAppIds.size() != 0) {
                mNetd.trafficSetNetPermForUids(INetd.PERMISSION_UPDATE_DEVICE_STATS,
                        ArrayUtils.convertToIntArray(updateStatsPermissionAppIds));
            }
            if (noPermissionAppIds.size() != 0) {
                mNetd.trafficSetNetPermForUids(INetd.PERMISSION_NONE,
                        ArrayUtils.convertToIntArray(noPermissionAppIds));
            }
            if (uninstalledAppIds.size() != 0) {
                mNetd.trafficSetNetPermForUids(INetd.PERMISSION_UNINSTALLED,
                        ArrayUtils.convertToIntArray(uninstalledAppIds));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Pass appId list of special permission failed." + e);
        }
    }

    /** Should only be used by unit tests */
    @VisibleForTesting
    public Set<UidRange> getVpnUidRanges(String iface) {
        return mVpnUidRanges.get(iface);
    }

    /** Dump info to dumpsys */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Interface filtering rules:");
        pw.increaseIndent();
        for (Map.Entry<String, Set<UidRange>> vpn : mVpnUidRanges.entrySet()) {
            pw.println("Interface: " + vpn.getKey());
            pw.println("UIDs: " + vpn.getValue().toString());
            pw.println();
        }
        pw.decreaseIndent();
    }

    private static void log(String s) {
        if (DBG) {
            Log.d(TAG, s);
        }
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

    private static void loge(String s, Throwable e) {
        Log.e(TAG, s, e);
    }
}
