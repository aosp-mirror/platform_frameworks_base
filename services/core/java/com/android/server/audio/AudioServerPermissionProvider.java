/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.audio;

import static android.Manifest.permission.ACCESS_ULTRASOUND;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.CALL_AUDIO_INTERCEPTION;
import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.CAPTURE_AUDIO_OUTPUT;
import static android.Manifest.permission.CAPTURE_MEDIA_OUTPUT;
import static android.Manifest.permission.CAPTURE_TUNER_AUDIO_INPUT;
import static android.Manifest.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT;
import static android.Manifest.permission.MODIFY_AUDIO_ROUTING;
import static android.Manifest.permission.MODIFY_AUDIO_SETTINGS;
import static android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS;
import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;

import android.annotation.Nullable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.media.permission.INativePermissionController;
import com.android.media.permission.PermissionEnum;
import com.android.media.permission.UidPackageState;
import com.android.server.pm.pkg.PackageState;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** Responsible for synchronizing system server permission state to the native audioserver. */
public class AudioServerPermissionProvider {

    static final String[] MONITORED_PERMS = new String[PermissionEnum.ENUM_SIZE];

    static final byte[] HDS_PERMS = new byte[] {PermissionEnum.CAPTURE_AUDIO_HOTWORD,
            PermissionEnum.CAPTURE_AUDIO_OUTPUT, PermissionEnum.RECORD_AUDIO};

    static {
        MONITORED_PERMS[PermissionEnum.RECORD_AUDIO] = RECORD_AUDIO;
        MONITORED_PERMS[PermissionEnum.MODIFY_AUDIO_ROUTING] = MODIFY_AUDIO_ROUTING;
        MONITORED_PERMS[PermissionEnum.MODIFY_AUDIO_SETTINGS] = MODIFY_AUDIO_SETTINGS;
        MONITORED_PERMS[PermissionEnum.MODIFY_PHONE_STATE] = MODIFY_PHONE_STATE;
        MONITORED_PERMS[PermissionEnum.MODIFY_DEFAULT_AUDIO_EFFECTS] = MODIFY_DEFAULT_AUDIO_EFFECTS;
        MONITORED_PERMS[PermissionEnum.WRITE_SECURE_SETTINGS] = WRITE_SECURE_SETTINGS;
        MONITORED_PERMS[PermissionEnum.CALL_AUDIO_INTERCEPTION] = CALL_AUDIO_INTERCEPTION;
        MONITORED_PERMS[PermissionEnum.ACCESS_ULTRASOUND] = ACCESS_ULTRASOUND;
        MONITORED_PERMS[PermissionEnum.CAPTURE_AUDIO_OUTPUT] = CAPTURE_AUDIO_OUTPUT;
        MONITORED_PERMS[PermissionEnum.CAPTURE_MEDIA_OUTPUT] = CAPTURE_MEDIA_OUTPUT;
        MONITORED_PERMS[PermissionEnum.CAPTURE_AUDIO_HOTWORD] = CAPTURE_AUDIO_HOTWORD;
        MONITORED_PERMS[PermissionEnum.CAPTURE_TUNER_AUDIO_INPUT] = CAPTURE_TUNER_AUDIO_INPUT;
        MONITORED_PERMS[PermissionEnum.CAPTURE_VOICE_COMMUNICATION_OUTPUT] =
                CAPTURE_VOICE_COMMUNICATION_OUTPUT;
        MONITORED_PERMS[PermissionEnum.BLUETOOTH_CONNECT] = BLUETOOTH_CONNECT;
    }

    private final Object mLock = new Object();
    private final Supplier<int[]> mUserIdSupplier;
    private final BiPredicate<Integer, String> mPermissionPredicate;

    @GuardedBy("mLock")
    private INativePermissionController mDest;

    @GuardedBy("mLock")
    private final Map<Integer, Set<String>> mPackageMap;

    // Values are sorted
    @GuardedBy("mLock")
    private final int[][] mPermMap = new int[PermissionEnum.ENUM_SIZE][];

    @GuardedBy("mLock")
    private boolean mIsUpdateDeferred = true;

    @GuardedBy("mLock")
    private int mHdsUid = -1;

    /**
     * @param appInfos - PackageState for all apps on the device, used to populate init state
     */
    public AudioServerPermissionProvider(
            Collection<PackageState> appInfos,
            BiPredicate<Integer, String> permissionPredicate,
            Supplier<int[]> userIdSupplier) {
        for (int i = 0; i < PermissionEnum.ENUM_SIZE; i++) {
            Objects.requireNonNull(MONITORED_PERMS[i]);
        }
        mUserIdSupplier = userIdSupplier;
        mPermissionPredicate = permissionPredicate;
        // Initialize the package state
        mPackageMap = generatePackageMappings(appInfos);
    }

    /**
     * Called whenever audioserver starts (or started before us)
     *
     * @param pc - The permission controller interface from audioserver, which we push updates to
     */
    public void onServiceStart(@Nullable INativePermissionController pc) {
        if (pc == null) return;
        synchronized (mLock) {
            mDest = pc;
            resetNativePackageState();
            try {
                for (byte i = 0; i < PermissionEnum.ENUM_SIZE; i++) {
                    if (mIsUpdateDeferred) {
                        mPermMap[i] = getUidsHoldingPerm(i);
                    }
                    mDest.populatePermissionState(i, mPermMap[i]);
                }
                mIsUpdateDeferred = false;
            } catch (RemoteException e) {
                // We will re-init the state when the service comes back up
                mDest = null;
            }
        }
    }

    /**
     * Called when a package is added or removed
     *
     * @param uid - uid of modified package (only app-id matters)
     * @param packageName - the (new) packageName
     * @param isRemove - true if the package is being removed, false if it is being added
     */
    public void onModifyPackageState(int uid, String packageName, boolean isRemove) {
        // No point in maintaining package mappings for uids of different users
        uid = UserHandle.getAppId(uid);
        synchronized (mLock) {
            // Update state
            Set<String> packages;
            if (!isRemove) {
                packages = mPackageMap.computeIfAbsent(uid, unused -> new ArraySet(1));
                packages.add(packageName);
            } else {
                packages = mPackageMap.get(uid);
                if (packages != null) {
                    packages.remove(packageName);
                    if (packages.isEmpty()) mPackageMap.remove(uid);
                }
            }
            // Push state to destination
            if (mDest == null) {
                return;
            }
            var state = new UidPackageState();
            state.uid = uid;
            state.packageNames = packages != null ? List.copyOf(packages) : Collections.emptyList();
            try {
                mDest.updatePackagesForUid(state);
            } catch (RemoteException e) {
                // We will re-init the state when the service comes back up
                mDest = null;
            }
        }
    }

    /** Called whenever any package/permission changes occur which invalidate uids holding perms */
    public void onPermissionStateChanged() {
        synchronized (mLock) {
            if (mDest == null) {
                mIsUpdateDeferred = true;
                return;
            }
            try {
                for (byte i = 0; i < PermissionEnum.ENUM_SIZE; i++) {
                    var newPerms = getUidsHoldingPerm(i);
                    if (!Arrays.equals(newPerms, mPermMap[i])) {
                        mPermMap[i] = newPerms;
                        mDest.populatePermissionState(i, newPerms);
                    }
                }
            } catch (RemoteException e) {
                // We will re-init the state when the service comes back up
                mDest = null;
                // We didn't necessarily finish
                mIsUpdateDeferred = true;
            }
        }
    }

    public void setIsolatedServiceUid(int uid, int owningUid) {
        synchronized (mLock) {
            if (mHdsUid == uid) return;
            var packageNameSet = mPackageMap.get(owningUid);
            if (packageNameSet == null) return;
            var packageName = packageNameSet.iterator().next();
            onModifyPackageState(uid, packageName, /* isRemove= */ false);
            // permissions
            mHdsUid = uid;
            if (mDest == null) {
                mIsUpdateDeferred = true;
                return;
            }
            try {
                for (byte perm : HDS_PERMS) {
                    int[] newPerms = new int[mPermMap[perm].length + 1];
                    System.arraycopy(mPermMap[perm], 0, newPerms, 0, mPermMap[perm].length);
                    newPerms[newPerms.length - 1] = mHdsUid;
                    Arrays.sort(newPerms);
                    mPermMap[perm] = newPerms;
                    mDest.populatePermissionState(perm, newPerms);
                }
            } catch (RemoteException e) {
                // We will re-init the state when the service comes back up
                mDest = null;
                // We didn't necessarily finish
                mIsUpdateDeferred = true;
            }
        }
    }

    public void clearIsolatedServiceUid(int uid) {
        synchronized (mLock) {
            if (mHdsUid != uid) return;
            var packageNameSet = mPackageMap.get(uid);
            if (packageNameSet == null) return;
            var packageName = packageNameSet.iterator().next();
            onModifyPackageState(uid, packageName, /* isRemove= */ true);
            // permissions
            if (mDest == null) {
                mIsUpdateDeferred = true;
                return;
            }
            try {
                for (byte perm : HDS_PERMS) {
                    int[] newPerms = new int[mPermMap[perm].length - 1];
                    int ind = Arrays.binarySearch(mPermMap[perm], uid);
                    if (ind < 0) continue;
                    System.arraycopy(mPermMap[perm], 0, newPerms, 0, ind);
                    System.arraycopy(mPermMap[perm], ind + 1, newPerms, ind,
                            mPermMap[perm].length - ind - 1);
                    mPermMap[perm] = newPerms;
                    mDest.populatePermissionState(perm, newPerms);
                }
            } catch (RemoteException e) {
                // We will re-init the state when the service comes back up
                mDest = null;
                // We didn't necessarily finish
                mIsUpdateDeferred = true;
            }
            mHdsUid = -1;
        }
    }

    private boolean isSpecialHdsPermission(int perm) {
        for (var hdsPerm : HDS_PERMS) {
            if (perm == hdsPerm) return true;
        }
        return false;
    }

    /** Called when full syncing package state to audioserver. */
    @GuardedBy("mLock")
    private void resetNativePackageState() {
        if (mDest == null) return;
        List<UidPackageState> states =
                mPackageMap.entrySet().stream()
                        .map(
                                entry -> {
                                    UidPackageState state = new UidPackageState();
                                    state.uid = entry.getKey();
                                    state.packageNames = List.copyOf(entry.getValue());
                                    return state;
                                })
                        .toList();
        try {
            mDest.populatePackagesForUids(states);
        } catch (RemoteException e) {
            // We will re-init the state when the service comes back up
            mDest = null;
        }
    }

    @GuardedBy("mLock")
    /** Return all uids (not app-ids) which currently hold a given permission. Not app-op aware */
    private int[] getUidsHoldingPerm(int perm) {
        IntArray acc = new IntArray();
        for (int userId : mUserIdSupplier.get()) {
            for (int appId : mPackageMap.keySet()) {
                int uid = UserHandle.getUid(userId, appId);
                if (mPermissionPredicate.test(uid, MONITORED_PERMS[perm])) {
                    acc.add(uid);
                }
            }
        }
        if (isSpecialHdsPermission(perm) && mHdsUid != -1) {
            acc.add(mHdsUid);
        }
        var unwrapped = acc.toArray();
        Arrays.sort(unwrapped);
        return unwrapped;
    }

    /**
     * Aggregation operation on all package states list: groups by states by app-id and merges the
     * packageName for each state into an ArraySet.
     */
    private static Map<Integer, Set<String>> generatePackageMappings(
            Collection<PackageState> appInfos) {
        Collector<PackageState, Object, Set<String>> reducer =
                Collectors.mapping(
                        (PackageState p) -> p.getPackageName(),
                        Collectors.toCollection(() -> new ArraySet(1)));

        return appInfos.stream()
                .collect(
                        Collectors.groupingBy(
                                /* predicate */ (PackageState p) -> p.getAppId(),
                                /* factory */ HashMap::new,
                                /* downstream collector */ reducer));
    }
}
