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

package com.android.server.pm.permission;

import static android.os.Process.INVALID_UID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributionFlags;
import android.app.AppOpsManagerInternal.CheckOpsDelegate;
import android.app.SyncNotedAppOp;
import android.content.AttributionSource;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.DodecFunction;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.OctFunction;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.TriFunction;
import com.android.internal.util.function.UndecFunction;
import com.android.server.LocalServices;
import com.android.server.pm.permission.PermissionManagerServiceInternal.CheckPermissionDelegate;

import java.util.List;
import java.util.Map;

/**
 * Interface to intercept incoming parameters and outgoing results to permission and appop checks
 */
public interface AccessCheckDelegate extends CheckPermissionDelegate, CheckOpsDelegate {

    /**
     * Assigns the package whose permissions are delegated the state of Shell's permissions.
     *
     * @param delegateUid the UID whose permissions are delegated to shell
     * @param packageName the name of the package whose permissions are delegated to shell
     * @param permissions the set of permissions to delegate to shell. If null then all
     *                    permission will be delegated
     */
    void setShellPermissionDelegate(int delegateUid, @NonNull String packageName,
            @Nullable String[] permissions);

    /**
     * Removes the assigned Shell permission delegate.
     */
    void removeShellPermissionDelegate();

    /**
     * @return a list of permissions delegated to Shell's permission state
     */
    @NonNull
    List<String> getDelegatedPermissionNames();

    /**
     * @return whether there exists a Shell permission delegate
     */
    boolean hasShellPermissionDelegate();

    /**
     * @param uid the UID to check
     * @return whether the UID's permissions are delegated to Shell's and the owner of overrides
     */
    boolean isDelegateAndOwnerUid(int uid);

    /**
     * @param uid the UID to check
     * @param packageName the package to check
     * @return whether the UID and package combination's permissions are delegated to Shell's
     * permissions
     */
    boolean isDelegatePackage(int uid, @NonNull String packageName);

    /**
     * Adds permission to be overridden to the given state.
     *
     * @param ownerUid the UID of the app who assigned the permission override
     * @param uid The UID of the app whose permission will be overridden
     * @param permission The permission whose state will be overridden
     * @param result The state to override the permission to
     */
    void addOverridePermissionState(int ownerUid, int uid, @NonNull String permission,
            int result);

    /**
     * Removes overridden permission. UiAutomation must be connected to root user.
     *
     * @param uid The UID of the app whose permission is overridden
     * @param permission The permission whose state will no longer be overridden
     *
     * @hide
     */
    void removeOverridePermissionState(int uid, @NonNull String permission);

    /**
     * Clears all overridden permissions for the given UID.
     *
     * @param uid The UID of the app whose permissions will no longer be overridden
     */
    void clearOverridePermissionStates(int uid);

    /**
     * Clears all overridden permissions on the device.
     */
    void clearAllOverridePermissionStates();

    /**
     * @return whether there exists any permission overrides
     */
    boolean hasOverriddenPermissions();

    /**
     * @return whether there exists permissions delegated to Shell's permissions or overridden
     */
    boolean hasDelegateOrOverrides();

    class AccessCheckDelegateImpl implements AccessCheckDelegate {
        public static final String SHELL_PKG = "com.android.shell";
        private int mDelegateAndOwnerUid = INVALID_UID;
        @Nullable
        private String mDelegatePackage;
        @Nullable
        private String[] mDelegatePermissions;
        boolean mDelegateAllPermissions;
        @Nullable
        private SparseArray<ArrayMap<String, Integer>> mOverridePermissionStates;

        @Override
        public void setShellPermissionDelegate(int uid, @NonNull String packageName,
                @Nullable String[] permissions) {
            mDelegateAndOwnerUid = uid;
            mDelegatePackage = packageName;
            mDelegatePermissions = permissions;
            mDelegateAllPermissions = permissions == null;
            PackageManager.invalidatePackageInfoCache();
        }

        @Override
        public void removeShellPermissionDelegate() {
            mDelegatePackage = null;
            mDelegatePermissions = null;
            mDelegateAllPermissions = false;
            PackageManager.invalidatePackageInfoCache();
        }

        @Override
        public void addOverridePermissionState(int ownerUid, int uid, @NonNull String permission,
                int state) {
            if (mOverridePermissionStates == null) {
                mDelegateAndOwnerUid = ownerUid;
                mOverridePermissionStates = new SparseArray<>();
            }

            int uidIdx = mOverridePermissionStates.indexOfKey(uid);
            ArrayMap<String, Integer> perUidOverrides;
            if (uidIdx < 0) {
                perUidOverrides = new ArrayMap<>();
                mOverridePermissionStates.put(uid, perUidOverrides);
            } else {
                perUidOverrides = mOverridePermissionStates.valueAt(uidIdx);
            }

            perUidOverrides.put(permission, state);
            PackageManager.invalidatePackageInfoCache();
        }

        @Override
        public void removeOverridePermissionState(int uid, @NonNull String permission) {
            if (mOverridePermissionStates == null) {
                return;
            }

            ArrayMap<String, Integer> perUidOverrides = mOverridePermissionStates.get(uid);

            if (perUidOverrides == null) {
                return;
            }

            perUidOverrides.remove(permission);
            PackageManager.invalidatePackageInfoCache();

            if (perUidOverrides.isEmpty()) {
                mOverridePermissionStates.remove(uid);
            }
            if (mOverridePermissionStates.size() == 0) {
                mOverridePermissionStates = null;
            }
        }

        @Override
        public void clearOverridePermissionStates(int uid) {
            if (mOverridePermissionStates == null) {
                return;
            }

            mOverridePermissionStates.remove(uid);
            PackageManager.invalidatePackageInfoCache();

            if (mOverridePermissionStates.size() == 0) {
                mOverridePermissionStates = null;
            }
        }

        @Override
        public void clearAllOverridePermissionStates() {
            mOverridePermissionStates = null;
            PackageManager.invalidatePackageInfoCache();
        }

        @Override
        public List<String> getDelegatedPermissionNames() {
            return mDelegatePermissions == null ? null : List.of(mDelegatePermissions);
        }

        @Override
        public boolean hasShellPermissionDelegate() {
            return mDelegateAllPermissions || mDelegatePermissions != null;
        }

        @Override
        public boolean isDelegatePackage(int uid, @NonNull String packageName) {
            return mDelegateAndOwnerUid == uid && TextUtils.equals(mDelegatePackage, packageName);
        }

        @Override
        public boolean hasOverriddenPermissions() {
            return mOverridePermissionStates != null;
        }

        @Override
        public boolean isDelegateAndOwnerUid(int uid) {
            return uid == mDelegateAndOwnerUid;
        }

        @Override
        public boolean hasDelegateOrOverrides() {
            return hasShellPermissionDelegate() || hasOverriddenPermissions();
        }

        @Override
        public int checkPermission(@NonNull String packageName, @NonNull String permissionName,
                @NonNull String persistentDeviceId, @UserIdInt int userId,
                @NonNull QuadFunction<String, String, String, Integer, Integer> superImpl) {
            if (TextUtils.equals(mDelegatePackage, packageName) && !SHELL_PKG.equals(packageName)) {
                if (isDelegatePermission(permissionName)) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        return checkPermission(SHELL_PKG, permissionName,
                                persistentDeviceId, userId, superImpl);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }
            if (mOverridePermissionStates != null) {
                int uid = LocalServices.getService(PackageManagerInternal.class)
                                .getPackageUid(packageName, 0, userId);
                if (uid >= 0) {
                    Map<String, Integer> permissionGrants = mOverridePermissionStates.get(uid);
                    if (permissionGrants != null && permissionGrants.containsKey(permissionName)) {
                        return permissionGrants.get(permissionName);
                    }
                }
            }
            return superImpl.apply(packageName, permissionName, persistentDeviceId, userId);
        }

        @Override
        public int checkUidPermission(int uid, @NonNull String permissionName,
                @NonNull String persistentDeviceId,
                @NonNull TriFunction<Integer, String, String, Integer> superImpl) {
            if (uid == mDelegateAndOwnerUid && uid != Process.SHELL_UID) {
                if (isDelegatePermission(permissionName)) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        return checkUidPermission(Process.SHELL_UID, permissionName,
                                persistentDeviceId, superImpl);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }
            if (mOverridePermissionStates != null) {
                Map<String, Integer> permissionGrants = mOverridePermissionStates.get(uid);
                if (permissionGrants != null && permissionGrants.containsKey(permissionName)) {
                    return permissionGrants.get(permissionName);
                }
            }
            return superImpl.apply(uid, permissionName, persistentDeviceId);
        }

        @Override
        public int checkOperation(int code, int uid, @Nullable String packageName,
                @Nullable String attributionTag, int virtualDeviceId, boolean raw,
                @NonNull HexFunction<Integer, Integer, String, String, Integer, Boolean, Integer>
                        superImpl) {
            if (uid == mDelegateAndOwnerUid && isDelegateOp(code)) {
                final int shellUid = UserHandle.getUid(UserHandle.getUserId(uid),
                        Process.SHELL_UID);
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(code, shellUid, "com.android.shell", null,
                            virtualDeviceId, raw);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(code, uid, packageName, attributionTag, virtualDeviceId, raw);
        }

        @Override
        public int checkAudioOperation(int code, int usage, int uid, @Nullable String packageName,
                @NonNull QuadFunction<Integer, Integer, Integer, String, Integer> superImpl) {
            if (uid == mDelegateAndOwnerUid && isDelegateOp(code)) {
                final int shellUid = UserHandle.getUid(UserHandle.getUserId(uid),
                        Process.SHELL_UID);
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(code, usage, shellUid, "com.android.shell");
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(code, usage, uid, packageName);
        }

        @Override
        public SyncNotedAppOp noteOperation(int code, int uid, @Nullable String packageName,
                @Nullable String featureId, int virtualDeviceId, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                @NonNull OctFunction<Integer, Integer, String, String, Integer, Boolean, String,
                        Boolean, SyncNotedAppOp> superImpl) {
            if (uid == mDelegateAndOwnerUid && isDelegateOp(code)) {
                final int shellUid = UserHandle.getUid(UserHandle.getUserId(uid),
                        Process.SHELL_UID);
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(code, shellUid, "com.android.shell", featureId,
                            virtualDeviceId, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(code, uid, packageName, featureId, virtualDeviceId,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage);
        }

        @Override
        public SyncNotedAppOp noteProxyOperation(int code,
                @NonNull AttributionSource attributionSource, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage, boolean skiProxyOperation,
                @NonNull HexFunction<Integer, AttributionSource, Boolean, String, Boolean,
                        Boolean, SyncNotedAppOp> superImpl) {
            if (attributionSource.getUid() == mDelegateAndOwnerUid && isDelegateOp(code)) {
                final int shellUid = UserHandle.getUid(
                        UserHandle.getUserId(attributionSource.getUid()), Process.SHELL_UID);
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(code,
                            new AttributionSource(shellUid, Process.INVALID_PID,
                                    "com.android.shell", attributionSource.getAttributionTag(),
                                    attributionSource.getToken(), /*renouncedPermissions*/ null,
                                    attributionSource.getDeviceId(), attributionSource.getNext()),
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            skiProxyOperation);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(code, attributionSource, shouldCollectAsyncNotedOp,
                    message, shouldCollectMessage, skiProxyOperation);
        }

        @Override
        public SyncNotedAppOp startOperation(@NonNull IBinder token, int code, int uid,
                @Nullable String packageName, @Nullable String attributionTag, int virtualDeviceId,
                boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                @AttributionFlags int attributionFlags, int attributionChainId,
                @NonNull DodecFunction<IBinder, Integer, Integer, String, String, Integer, Boolean,
                        Boolean, String, Boolean, Integer, Integer, SyncNotedAppOp> superImpl) {
            if (uid == mDelegateAndOwnerUid && isDelegateOp(code)) {
                final int shellUid = UserHandle.getUid(UserHandle.getUserId(uid),
                        Process.SHELL_UID);
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(token, code, shellUid, "com.android.shell",
                            attributionTag, virtualDeviceId, startIfModeDefault,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            attributionFlags, attributionChainId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(token, code, uid, packageName, attributionTag, virtualDeviceId,
                    startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    attributionFlags, attributionChainId);
        }

        @Override
        public SyncNotedAppOp startProxyOperation(@NonNull IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
                boolean shouldCollectAsyncNotedOp, @Nullable String message,
                boolean shouldCollectMessage, boolean skipProxyOperation,
                @AttributionFlags int proxyAttributionFlags,
                @AttributionFlags int proxiedAttributionFlags, int attributionChainId,
                @NonNull UndecFunction<IBinder, Integer, AttributionSource, Boolean,
                        Boolean, String, Boolean, Boolean, Integer, Integer, Integer,
                        SyncNotedAppOp> superImpl) {
            if (attributionSource.getUid() == mDelegateAndOwnerUid && isDelegateOp(code)) {
                final int shellUid = UserHandle.getUid(UserHandle.getUserId(
                        attributionSource.getUid()), Process.SHELL_UID);
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(clientId, code,
                            new AttributionSource(shellUid, Process.INVALID_PID,
                                    "com.android.shell", attributionSource.getAttributionTag(),
                                    attributionSource.getToken(), /*renouncedPermissions*/ null,
                                    attributionSource.getDeviceId(), attributionSource.getNext()),
                            startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                            proxiedAttributionFlags, attributionChainId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(clientId, code, attributionSource, startIfModeDefault,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage, skipProxyOperation,
                    proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);
        }

        @Override
        public void finishProxyOperation(@NonNull IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean skipProxyOperation,
                @NonNull QuadFunction<IBinder, Integer, AttributionSource, Boolean,
                        Void> superImpl) {
            if (attributionSource.getUid() == mDelegateAndOwnerUid && isDelegateOp(code)) {
                final int shellUid = UserHandle.getUid(UserHandle.getUserId(
                        attributionSource.getUid()), Process.SHELL_UID);
                final long identity = Binder.clearCallingIdentity();
                try {
                    superImpl.apply(clientId, code,
                            new AttributionSource(shellUid, Process.INVALID_PID,
                                    "com.android.shell", attributionSource.getAttributionTag(),
                                    attributionSource.getToken(), /*renouncedPermissions*/ null,
                                    attributionSource.getDeviceId(), attributionSource.getNext()),
                            skipProxyOperation);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            superImpl.apply(clientId, code, attributionSource, skipProxyOperation);
        }

        private boolean isDelegatePermission(@NonNull String permission) {
            // null permissions means all permissions are delegated
            return mDelegateAndOwnerUid != INVALID_UID
                    && (mDelegateAllPermissions
                    || ArrayUtils.contains(mDelegatePermissions, permission));
        }

        private boolean isDelegateOp(int code) {
            if (mDelegateAllPermissions) {
                return true;
            }
            // no permission for the op means the op is targeted
            final String permission = AppOpsManager.opToPermission(code);
            if (permission == null) {
                return true;
            }
            return isDelegatePermission(permission);
        }
    }
}
