/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN;
import static android.hardware.usb.DisplayPortAltModeInfo.LINK_TRAINING_STATUS_UNKNOWN;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_DEVICE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_HOST;
import static android.hardware.usb.UsbPortStatus.MODE_DFP;
import static android.hardware.usb.UsbPortStatus.MODE_DUAL;
import static android.hardware.usb.UsbPortStatus.MODE_UFP;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SINK;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SOURCE;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.IDisplayPortAltModeInfoListener;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.usb.UsbServiceDumpProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.FgThread;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;

import dalvik.annotation.optimization.NeverCompile;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * UsbService manages all USB related state, including both host and device support.
 * Host related events and calls are delegated to UsbHostManager, and device related
 * support is delegated to UsbDeviceManager.
 */
public class UsbService extends IUsbManager.Stub {

    public static class Lifecycle extends SystemService {
        private UsbService mUsbService;
        private final CompletableFuture<Void> mOnStartFinished = new CompletableFuture<>();
        private final CompletableFuture<Void> mOnActivityManagerPhaseFinished =
                new CompletableFuture<>();

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            SystemServerInitThreadPool.submit(() -> {
                mUsbService = new UsbService(getContext());
                publishBinderService(Context.USB_SERVICE, mUsbService);
                mOnStartFinished.complete(null);
            }, "UsbService$Lifecycle#onStart");
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                SystemServerInitThreadPool.submit(() -> {
                    mOnStartFinished.join();
                    mUsbService.systemReady();
                    mOnActivityManagerPhaseFinished.complete(null);
                }, "UsbService$Lifecycle#onBootPhase");
            } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
                mOnActivityManagerPhaseFinished.join();
                mUsbService.bootCompleted();
            }
        }

        @Override
        public void onUserSwitching(TargetUser from, TargetUser to) {
            FgThread.getHandler()
                    .postAtFrontOfQueue(() -> mUsbService.onSwitchUser(to.getUserIdentifier()));
        }

        @Override
        public void onUserStopping(TargetUser userInfo) {
            mUsbService.onStopUser(userInfo.getUserHandle());
        }

        @Override
        public void onUserUnlocking(TargetUser userInfo) {
            mUsbService.onUnlockUser(userInfo.getUserIdentifier());
        }
    }

    private static final String TAG = "UsbService";

    private final Context mContext;
    private final UserManager mUserManager;

    private UsbDeviceManager mDeviceManager;
    private UsbHostManager mHostManager;
    private UsbPortManager mPortManager;
    private final UsbAlsaManager mAlsaManager;

    private final UsbSettingsManager mSettingsManager;
    private final UsbPermissionManager mPermissionManager;

    static final int PACKAGE_MONITOR_OPERATION_ID = 1;
    static final int STRONG_AUTH_OPERATION_ID = 2;
    /**
     * The user id of the current user. There might be several profiles (with separate user ids)
     * per user.
     */
    @GuardedBy("mLock")
    private @UserIdInt int mCurrentUserId;

    private final Object mLock = new Object();

    // Key: USB port id
    // Value: A set of UIDs of requesters who request disabling usb data
    private final ArrayMap<String, ArraySet<Integer>> mUsbDisableRequesters = new ArrayMap<>();

    /**
     * @return the {@link UsbUserSettingsManager} for the given userId
     */
    UsbUserSettingsManager getSettingsForUser(@UserIdInt int userId) {
        return mSettingsManager.getSettingsForUser(userId);
    }

    /**
     * @return the {@link UsbUserPermissionManager} for the given userId
     */
    UsbUserPermissionManager getPermissionsForUser(@UserIdInt int userId) {
        return mPermissionManager.getPermissionsForUser(userId);
    }

    public UsbService(Context context) {
        mContext = context;

        mUserManager = context.getSystemService(UserManager.class);
        mSettingsManager = new UsbSettingsManager(context, this);
        mPermissionManager = new UsbPermissionManager(context, this);
        mAlsaManager = new UsbAlsaManager(context);

        final PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            mHostManager = new UsbHostManager(context, mAlsaManager, mPermissionManager);
        }
        if (new File("/sys/class/android_usb").exists()) {
            mDeviceManager = new UsbDeviceManager(context, mAlsaManager, mSettingsManager,
                    mPermissionManager);
        }
        if (mHostManager != null || mDeviceManager != null) {
            mPortManager = new UsbPortManager(context);
        }

        onSwitchUser(UserHandle.USER_SYSTEM);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                        .equals(action)) {
                    if (mDeviceManager != null) {
                        mDeviceManager.updateUserRestrictions();
                    }
                }
            }
        };

        final IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiverAsUser(receiver, UserHandle.ALL, filter, null, null);
    }

    /**
     * Set new {@link #mCurrentUserId} and propagate it to other modules.
     *
     * @param newUserId The user id of the new current user.
     */
    private void onSwitchUser(@UserIdInt int newUserId) {
        synchronized (mLock) {
            mCurrentUserId = newUserId;

            // The following two modules need to know about the current profile group. If they need
            // to distinguish by profile of the user, the id has to be passed in the call to the
            // module.
            UsbProfileGroupSettingsManager settings =
                    mSettingsManager.getSettingsForProfileGroup(UserHandle.of(newUserId));
            if (mHostManager != null) {
                mHostManager.setCurrentUserSettings(settings);
            }
            if (mDeviceManager != null) {
                mDeviceManager.setCurrentUser(newUserId, settings);
            }
        }
    }

    /**
     * Execute operations when a user is stopped.
     *
     * @param stoppedUser The user that is stopped
     */
    private void onStopUser(@NonNull UserHandle stoppedUser) {
        mSettingsManager.remove(stoppedUser);
    }

    public void systemReady() {
        mAlsaManager.systemReady();

        if (mDeviceManager != null) {
            mDeviceManager.systemReady();
        }
        if (mHostManager != null) {
            mHostManager.systemReady();
        }
        if (mPortManager != null) {
            mPortManager.systemReady();
        }
    }

    public void bootCompleted() {
        if (mDeviceManager != null) {
            mDeviceManager.bootCompleted();
        }
        if (android.hardware.usb.flags.Flags.enableUsbDataSignalStaking()) {
            new PackageUninstallMonitor()
                    .register(mContext, UserHandle.ALL, BackgroundThread.getHandler());

            new LockPatternUtils(mContext)
                    .registerStrongAuthTracker(new StrongAuthTracker(mContext,
                            BackgroundThread.getHandler().getLooper()));
        }
    }

    /** Called when a user is unlocked. */
    public void onUnlockUser(int user) {
        if (mDeviceManager != null) {
            mDeviceManager.onUnlockUser(user);
        }
    }

    /* Returns a list of all currently attached USB devices (host mdoe) */
    @Override
    public void getDeviceList(Bundle devices) {
        if (mHostManager != null) {
            mHostManager.getDeviceList(devices);
        }
    }

    /* Opens the specified USB device (host mode) */
    @Override
    public ParcelFileDescriptor openDevice(String deviceName, String packageName) {
        ParcelFileDescriptor fd = null;

        if (mHostManager != null) {
            if (deviceName != null) {
                int uid = Binder.getCallingUid();
                int pid = Binder.getCallingPid();
                int user = UserHandle.getUserId(uid);

                final long ident = clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        if (mUserManager.isSameProfileGroup(user, mCurrentUserId)) {
                            fd = mHostManager.openDevice(deviceName, getPermissionsForUser(user),
                                    packageName, pid, uid);
                        } else {
                            Slog.w(TAG, "Cannot open " + deviceName + " for user " + user
                                    + " as user is not active.");
                        }
                    }
                } finally {
                    restoreCallingIdentity(ident);
                }
            }
        }

        return fd;
    }

    /* returns the currently attached USB accessory (device mode) */
    @Override
    public UsbAccessory getCurrentAccessory() {
        if (mDeviceManager != null) {
            return mDeviceManager.getCurrentAccessory();
        } else {
            return null;
        }
    }

    /* opens the currently attached USB accessory (device mode) */
    @Override
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        if (mDeviceManager != null) {
            int uid = Binder.getCallingUid();
            int pid = Binder.getCallingPid();
            int user = UserHandle.getUserId(uid);

            final long ident = clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mUserManager.isSameProfileGroup(user, mCurrentUserId)) {
                        return mDeviceManager.openAccessory(accessory, getPermissionsForUser(user),
                                pid, uid);
                    } else {
                        Slog.w(TAG, "Cannot open " + accessory + " for user " + user
                                + " as user is not active.");
                    }
                }
            } finally {
                restoreCallingIdentity(ident);
            }
        }

        return null;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_MTP)
    /* Returns a dup of the control file descriptor for the given function. */
    @Override
    public ParcelFileDescriptor getControlFd(long function) {
        getControlFd_enforcePermission();
        return mDeviceManager.getControlFd(function);
    }

    @Override
    public void setDevicePackage(UsbDevice device, String packageName, int userId) {
        Objects.requireNonNull(device);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        UserHandle user = UserHandle.of(userId);
        final long token = Binder.clearCallingIdentity();
        try {
            mSettingsManager.getSettingsForProfileGroup(user).setDevicePackage(device, packageName,
                    user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void setAccessoryPackage(UsbAccessory accessory, String packageName, int userId) {
        Objects.requireNonNull(accessory);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        UserHandle user = UserHandle.of(userId);

        final long token = Binder.clearCallingIdentity();
        try {
            mSettingsManager.getSettingsForProfileGroup(user).setAccessoryPackage(accessory,
                    packageName, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void addDevicePackagesToPreferenceDenied(UsbDevice device, String[] packageNames,
            UserHandle user) {
        Objects.requireNonNull(device);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        Objects.requireNonNull(user);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long token = Binder.clearCallingIdentity();
        try {
            mSettingsManager.getSettingsForProfileGroup(user)
                    .addDevicePackagesToDenied(device, packageNames, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void addAccessoryPackagesToPreferenceDenied(UsbAccessory accessory,
            String[] packageNames, UserHandle user) {
        Objects.requireNonNull(accessory);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        Objects.requireNonNull(user);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long token = Binder.clearCallingIdentity();
        try {
            mSettingsManager.getSettingsForProfileGroup(user)
                    .addAccessoryPackagesToDenied(accessory, packageNames, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void removeDevicePackagesFromPreferenceDenied(UsbDevice device, String[] packageNames,
            UserHandle user) {
        Objects.requireNonNull(device);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        Objects.requireNonNull(user);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long token = Binder.clearCallingIdentity();
        try {
            mSettingsManager.getSettingsForProfileGroup(user)
                    .removeDevicePackagesFromDenied(device, packageNames, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void removeAccessoryPackagesFromPreferenceDenied(UsbAccessory accessory,
            String[] packageNames, UserHandle user) {
        Objects.requireNonNull(accessory);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        Objects.requireNonNull(user);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long token = Binder.clearCallingIdentity();
        try {
            mSettingsManager.getSettingsForProfileGroup(user)
                    .removeAccessoryPackagesFromDenied(accessory, packageNames, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void setDevicePersistentPermission(UsbDevice device, int uid, UserHandle user,
            boolean shouldBeGranted) {
        Objects.requireNonNull(device);
        Objects.requireNonNull(user);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long token = Binder.clearCallingIdentity();
        try {
            mPermissionManager.getPermissionsForUser(user).setDevicePersistentPermission(device,
                    uid, shouldBeGranted);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void setAccessoryPersistentPermission(UsbAccessory accessory, int uid,
            UserHandle user, boolean shouldBeGranted) {
        Objects.requireNonNull(accessory);
        Objects.requireNonNull(user);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long token = Binder.clearCallingIdentity();
        try {
            mPermissionManager.getPermissionsForUser(user).setAccessoryPersistentPermission(
                    accessory, uid, shouldBeGranted);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean hasDevicePermission(UsbDevice device, String packageName) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            return getPermissionsForUser(userId).hasPermission(device, packageName, pid, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public boolean hasDevicePermissionWithIdentity(UsbDevice device, String packageName,
            int pid, int uid) {
        hasDevicePermissionWithIdentity_enforcePermission();

        final int userId = UserHandle.getUserId(uid);
        return getPermissionsForUser(userId).hasPermission(device, packageName, pid, uid);
    }

    @Override
    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            return getPermissionsForUser(userId).hasPermission(accessory, pid, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public boolean hasAccessoryPermissionWithIdentity(UsbAccessory accessory, int pid, int uid) {
        hasAccessoryPermissionWithIdentity_enforcePermission();

        final int userId = UserHandle.getUserId(uid);
        return getPermissionsForUser(userId).hasPermission(accessory, pid, uid);
    }

    @Override
    public void requestDevicePermission(UsbDevice device, String packageName, PendingIntent pi) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            getPermissionsForUser(userId).requestPermission(device, packageName, pi, pid, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void requestAccessoryPermission(
            UsbAccessory accessory, String packageName, PendingIntent pi) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            getPermissionsForUser(userId).requestPermission(accessory, packageName, pi, pid, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public void grantDevicePermission(UsbDevice device, int uid) {
        grantDevicePermission_enforcePermission();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            getPermissionsForUser(userId).grantDevicePermission(device, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        grantAccessoryPermission_enforcePermission();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            getPermissionsForUser(userId).grantAccessoryPermission(accessory, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean hasDefaults(String packageName, int userId) {
        packageName = Preconditions.checkStringNotEmpty(packageName);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        UserHandle user = UserHandle.of(userId);

        final long token = Binder.clearCallingIdentity();
        try {
            return mSettingsManager.getSettingsForProfileGroup(user).hasDefaults(packageName, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void clearDefaults(String packageName, int userId) {
        packageName = Preconditions.checkStringNotEmpty(packageName);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        UserHandle user = UserHandle.of(userId);

        final long token = Binder.clearCallingIdentity();
        try {
            mSettingsManager.getSettingsForProfileGroup(user).clearDefaults(packageName, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public void setCurrentFunctions(long functions, int operationId) {
        setCurrentFunctions_enforcePermission();
        Preconditions.checkArgument(UsbManager.areSettableFunctions(functions));
        Preconditions.checkState(mDeviceManager != null);
        mDeviceManager.setCurrentFunctions(functions, operationId);
    }

    @Override
    public void setCurrentFunction(String functions, boolean usbDataUnlocked, int operationId) {
        setCurrentFunctions(UsbManager.usbFunctionsFromString(functions), operationId);
    }

    @Override
    public boolean isFunctionEnabled(String function) {
        return (getCurrentFunctions() & UsbManager.usbFunctionsFromString(function)) != 0;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public long getCurrentFunctions() {
        getCurrentFunctions_enforcePermission();
        Preconditions.checkState(mDeviceManager != null);
        return mDeviceManager.getCurrentFunctions();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public void setScreenUnlockedFunctions(long functions) {
        setScreenUnlockedFunctions_enforcePermission();
        Preconditions.checkArgument(UsbManager.areSettableFunctions(functions));
        Preconditions.checkState(mDeviceManager != null);

        mDeviceManager.setScreenUnlockedFunctions(functions);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public long getScreenUnlockedFunctions() {
        getScreenUnlockedFunctions_enforcePermission();
        Preconditions.checkState(mDeviceManager != null);
        return mDeviceManager.getScreenUnlockedFunctions();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public int getCurrentUsbSpeed() {
        getCurrentUsbSpeed_enforcePermission();
        Preconditions.checkNotNull(mDeviceManager, "DeviceManager must not be null");

        final long ident = Binder.clearCallingIdentity();
        try {
            return mDeviceManager.getCurrentUsbSpeed();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public int getGadgetHalVersion() {
        getGadgetHalVersion_enforcePermission();
        Preconditions.checkNotNull(mDeviceManager, "DeviceManager must not be null");

        final long ident = Binder.clearCallingIdentity();
        try {
            return mDeviceManager.getGadgetHalVersion();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public void resetUsbGadget() {
        resetUsbGadget_enforcePermission();
        Preconditions.checkNotNull(mDeviceManager, "DeviceManager must not be null");

        final long ident = Binder.clearCallingIdentity();
        try {
            mDeviceManager.resetUsbGadget();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void resetUsbPort(String portId, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(portId, "resetUsbPort: portId must not be null. opId:"
                + operationId);
        Objects.requireNonNull(callback, "resetUsbPort: callback must not be null. opId:"
                + operationId);
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();

        try {
            if (mPortManager != null) {
                mPortManager.resetUsbPort(portId, operationId, callback, null);
            } else {
                try {
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                } catch (RemoteException e) {
                    Slog.e(TAG, "resetUsbPort: Failed to call onOperationComplete", e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public List<ParcelableUsbPort> getPorts() {
        getPorts_enforcePermission();

        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPortManager == null) {
                return null;
            } else {
                final UsbPort[] ports = mPortManager.getPorts();

                final int numPorts = ports.length;
                ArrayList<ParcelableUsbPort> parcelablePorts = new ArrayList<>();
                for (int i = 0; i < numPorts; i++) {
                    parcelablePorts.add(ParcelableUsbPort.of(ports[i]));
                }

                return parcelablePorts;
            }

        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public UsbPortStatus getPortStatus(String portId) {
        Objects.requireNonNull(portId, "portId must not be null");
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            return mPortManager != null ? mPortManager.getPortStatus(portId) : null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public boolean isModeChangeSupported(String portId) {
        isModeChangeSupported_enforcePermission();
        Objects.requireNonNull(portId, "portId must not be null");

        final long ident = Binder.clearCallingIdentity();
        try {
            return mPortManager != null ? mPortManager.isModeChangeSupported(portId) : false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setPortRoles(String portId, int powerRole, int dataRole) {
        Objects.requireNonNull(portId, "portId must not be null");
        UsbPort.checkRoles(powerRole, dataRole);
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPortManager != null) {
                mPortManager.setPortRoles(portId, powerRole, dataRole, null);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void enableLimitPowerTransfer(String portId, boolean limit, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(portId, "portId must not be null. opID:" + operationId);
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPortManager != null) {
                mPortManager.enableLimitPowerTransfer(portId, limit, operationId, callback, null);
            } else {
                try {
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                } catch (RemoteException e) {
                    Slog.e(TAG, "enableLimitPowerTransfer: Failed to call onOperationComplete", e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void enableContaminantDetection(String portId, boolean enable) {
        Objects.requireNonNull(portId, "portId must not be null");
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPortManager != null) {
                mPortManager.enableContaminantDetection(portId, enable, null);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public int getUsbHalVersion() {
        getUsbHalVersion_enforcePermission();

        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPortManager != null) {
                return mPortManager.getUsbHalVersion();
            } else {
                return UsbManager.USB_HAL_NOT_SUPPORTED;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean enableUsbData(String portId, boolean enable, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(portId, "enableUsbData: portId must not be null. opId:"
                + operationId);
        Objects.requireNonNull(callback, "enableUsbData: callback must not be null. opId:"
                + operationId);
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        if (android.hardware.usb.flags.Flags.enableUsbDataSignalStaking()) {
            if (!shouldUpdateUsbSignaling(portId, enable, Binder.getCallingUid())) return false;
        }

        final long ident = Binder.clearCallingIdentity();
        boolean wait;
        try {
            if (mPortManager != null) {
                wait = mPortManager.enableUsbData(portId, enable, operationId, callback, null);
            } else {
                wait = false;
                try {
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                } catch (RemoteException e) {
                    Slog.e(TAG, "enableUsbData: Failed to call onOperationComplete", e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return wait;
    }

    /**
     * If enable = true, exclude UID from update list.
     * If enable = false, include UID in update list.
     * Return false if enable = true and the list is empty (no updates).
     * Return true otherwise (let downstream decide on updates).
     */
    private boolean shouldUpdateUsbSignaling(String portId, boolean enable, int uid) {
        synchronized (mUsbDisableRequesters) {
            if (!mUsbDisableRequesters.containsKey(portId)) {
                mUsbDisableRequesters.put(portId, new ArraySet<>());
            }

            ArraySet<Integer> uidsOfDisableRequesters = mUsbDisableRequesters.get(portId);

            if (enable) {
                uidsOfDisableRequesters.remove(uid);
                // re-enable USB port (return true) if there are no other disable requesters
                return uidsOfDisableRequesters.isEmpty();
            } else {
                uidsOfDisableRequesters.add(uid);
            }
        }
        return true;
    }

    @Override
    public void enableUsbDataWhileDocked(String portId, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(portId, "enableUsbDataWhileDocked: portId must not be null. opId:"
                + operationId);
        Objects.requireNonNull(callback,
                "enableUsbDataWhileDocked: callback must not be null. opId:"
                + operationId);
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        final long ident = Binder.clearCallingIdentity();
        boolean wait;
        try {
            if (mPortManager != null) {
                mPortManager.enableUsbDataWhileDocked(portId, operationId, callback, null);
            } else {
                try {
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                } catch (RemoteException e) {
                    Slog.e(TAG, "enableUsbData: Failed to call onOperationComplete", e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USB)
    @Override
    public void setUsbDeviceConnectionHandler(ComponentName usbDeviceConnectionHandler) {
        setUsbDeviceConnectionHandler_enforcePermission();
        synchronized (mLock) {
            if (mCurrentUserId == UserHandle.getCallingUserId()) {
                if (mHostManager != null) {
                    mHostManager.setUsbDeviceConnectionHandler(usbDeviceConnectionHandler);
                }
            } else {
                throw new IllegalArgumentException("Only the current user can register a usb " +
                        "connection handler");
            }
        }
    }

    @Override
    public boolean registerForDisplayPortEvents(
            @NonNull IDisplayPortAltModeInfoListener listener) {
        Objects.requireNonNull(listener, "registerForDisplayPortEvents: listener " +
                "must not be null.");

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPortManager != null) {
                return mPortManager.registerForDisplayPortEvents(listener);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return false;
    }

    @Override
    public void unregisterForDisplayPortEvents(
            @NonNull IDisplayPortAltModeInfoListener listener) {
        Objects.requireNonNull(listener, "unregisterForDisplayPortEvents: listener " +
                "must not be null.");

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPortManager != null) {
                mPortManager.unregisterForDisplayPortEvents(listener);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }


    @NeverCompile // Avoid size overhead of debugging code.
    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        final long ident = Binder.clearCallingIdentity();
        try {
            ArraySet<String> argsSet = new ArraySet<>();
            Collections.addAll(argsSet, args);

            boolean dumpAsProto = false;
            if (argsSet.contains("--proto")) {
                dumpAsProto = true;
            }

            if (args == null || args.length == 0 || args[0].equals("-a") || dumpAsProto) {
                DualDumpOutputStream dump;
                if (dumpAsProto) {
                    dump = new DualDumpOutputStream(new ProtoOutputStream(fd));
                } else {
                    pw.println("USB MANAGER STATE (dumpsys usb):");

                    dump = new DualDumpOutputStream(new IndentingPrintWriter(pw, "  "));
                }

                if (mDeviceManager != null) {
                    mDeviceManager.dump(dump, "device_manager", UsbServiceDumpProto.DEVICE_MANAGER);
                }
                if (mHostManager != null) {
                    mHostManager.dump(dump, "host_manager", UsbServiceDumpProto.HOST_MANAGER);
                }
                if (mPortManager != null) {
                    mPortManager.dump(dump, "port_manager", UsbServiceDumpProto.PORT_MANAGER);
                }
                mAlsaManager.dump(dump, "alsa_manager", UsbServiceDumpProto.ALSA_MANAGER);

                mSettingsManager.dump(dump, "settings_manager",
                        UsbServiceDumpProto.SETTINGS_MANAGER);
                mPermissionManager.dump(dump, "permissions_manager",
                        UsbServiceDumpProto.PERMISSIONS_MANAGER);
                dump.flush();
            } else if ("set-port-roles".equals(args[0]) && args.length == 4) {
                final String portId = args[1];
                final int powerRole;
                switch (args[2]) {
                    case "source":
                        powerRole = POWER_ROLE_SOURCE;
                        break;
                    case "sink":
                        powerRole = POWER_ROLE_SINK;
                        break;
                    case "no-power":
                        powerRole = 0;
                        break;
                    default:
                        pw.println("Invalid power role: " + args[2]);
                        return;
                }
                final int dataRole;
                switch (args[3]) {
                    case "host":
                        dataRole = DATA_ROLE_HOST;
                        break;
                    case "device":
                        dataRole = DATA_ROLE_DEVICE;
                        break;
                    case "no-data":
                        dataRole = 0;
                        break;
                    default:
                        pw.println("Invalid data role: " + args[3]);
                        return;
                }
                if (mPortManager != null) {
                    mPortManager.setPortRoles(portId, powerRole, dataRole, pw);
                    // Note: It might take some time for the side-effects of this operation
                    // to be fully applied by the kernel since the driver may need to
                    // renegotiate the USB port mode.  If this proves to be an issue
                    // during debugging, it might be worth adding a sleep here before
                    // dumping the new state.
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
        } else if ("add-port".equals(args[0]) && args.length >= 3) {
                final String portId = args[1];
                final int supportedModes;

                int i;
                boolean supportsComplianceWarnings = false;
                boolean supportsDisplayPortAltMode = false;
                switch (args[2]) {
                    case "ufp":
                        supportedModes = MODE_UFP;
                        break;
                    case "dfp":
                        supportedModes = MODE_DFP;
                        break;
                    case "dual":
                        supportedModes = MODE_DUAL;
                        break;
                    case "none":
                        supportedModes = 0;
                        break;
                    default:
                        pw.println("Invalid mode: " + args[2]);
                        return;
                }
                for (i=3; i<args.length; i++) {
                    switch (args[i]) {
                    case "--compliance-warnings":
                        supportsComplianceWarnings = true;
                        continue;
                    case "--displayport":
                        supportsDisplayPortAltMode = true;
                        continue;
                    default:
                        pw.println("Invalid Identifier: " + args[i]);
                    }
                }
                if (mPortManager != null) {
                    mPortManager.addSimulatedPort(portId, supportedModes,
                        supportsComplianceWarnings, supportsDisplayPortAltMode,
                        pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("connect-port".equals(args[0]) && args.length == 5) {
                final String portId = args[1];
                final int mode;
                final boolean canChangeMode = args[2].endsWith("?");
                switch (canChangeMode ? removeLastChar(args[2]) : args[2]) {
                    case "ufp":
                        mode = MODE_UFP;
                        break;
                    case "dfp":
                        mode = MODE_DFP;
                        break;
                    default:
                        pw.println("Invalid mode: " + args[2]);
                        return;
                }
                final int powerRole;
                final boolean canChangePowerRole = args[3].endsWith("?");
                switch (canChangePowerRole ? removeLastChar(args[3]) : args[3]) {
                    case "source":
                        powerRole = POWER_ROLE_SOURCE;
                        break;
                    case "sink":
                        powerRole = POWER_ROLE_SINK;
                        break;
                    default:
                        pw.println("Invalid power role: " + args[3]);
                        return;
                }
                final int dataRole;
                final boolean canChangeDataRole = args[4].endsWith("?");
                switch (canChangeDataRole ? removeLastChar(args[4]) : args[4]) {
                    case "host":
                        dataRole = DATA_ROLE_HOST;
                        break;
                    case "device":
                        dataRole = DATA_ROLE_DEVICE;
                        break;
                    default:
                        pw.println("Invalid data role: " + args[4]);
                        return;
                }
                if (mPortManager != null) {
                    mPortManager.connectSimulatedPort(portId, mode, canChangeMode,
                            powerRole, canChangePowerRole, dataRole, canChangeDataRole, pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("disconnect-port".equals(args[0]) && args.length == 2) {
                final String portId = args[1];
                if (mPortManager != null) {
                    mPortManager.disconnectSimulatedPort(portId, pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("remove-port".equals(args[0]) && args.length == 2) {
                final String portId = args[1];
                if (mPortManager != null) {
                    mPortManager.removeSimulatedPort(portId, pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("reset".equals(args[0]) && args.length == 1) {
                if (mPortManager != null) {
                    mPortManager.resetSimulation(pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("set-contaminant-status".equals(args[0]) && args.length == 3) {
                final String portId = args[1];
                final Boolean wet = Boolean.parseBoolean(args[2]);
                if (mPortManager != null) {
                    mPortManager.simulateContaminantStatus(portId, wet, pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("set-compliance-reasons".equals(args[0]) && args.length == 3) {
                final String portId = args[1];
                final String complianceWarnings = args[2];
                if (mPortManager != null) {
                    mPortManager.simulateComplianceWarnings(portId, complianceWarnings, pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("clear-compliance-reasons".equals(args[0]) && args.length == 2) {
                final String portId = args[1];
                if (mPortManager != null) {
                    mPortManager.simulateComplianceWarnings(portId, "", pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("set-displayport-status".equals(args[0]) && args.length == 7) {
                final String portId = args[1];
                final int partnerSinkStatus = Integer.parseInt(args[2]);
                final int cableStatus = Integer.parseInt(args[3]);
                final int displayPortNumLanes = Integer.parseInt(args[4]);
                final boolean hpd = Boolean.parseBoolean(args[5]);
                final int linkTrainingStatus = Integer.parseInt(args[6]);
                if (mPortManager != null) {
                    mPortManager.simulateDisplayPortAltModeInfo(portId,
                            partnerSinkStatus, cableStatus, displayPortNumLanes,
                            hpd, linkTrainingStatus, pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("reset-displayport-status".equals(args[0]) && args.length == 2) {
                final String portId = args[1];
                if (mPortManager != null) {
                    mPortManager.simulateDisplayPortAltModeInfo(portId,
                            DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN,
                            DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN,
                            0,
                            false,
                            LINK_TRAINING_STATUS_UNKNOWN,
                            pw);
                    pw.println();
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("enable-usb-data".equals(args[0]) && args.length == 3) {
                final String portId = args[1];
                final boolean enable = Boolean.parseBoolean(args[2]);

                if (mPortManager != null) {
                    for (UsbPort p : mPortManager.getPorts()) {
                        if (p.getId().equals(portId)) {
                            int res = p.enableUsbData(enable);
                            Slog.i(TAG, "enableUsbData " + portId + " status " + res);
                            break;
                        }
                    }
                }

            } else if ("ports".equals(args[0]) && args.length == 1) {
                if (mPortManager != null) {
                    mPortManager.dump(new DualDumpOutputStream(new IndentingPrintWriter(pw, "  ")),
                            "", 0);
                }
            } else if ("dump-descriptors".equals(args[0])) {
                mHostManager.dumpDescriptors(pw, args);
            } else {
                pw.println("Dump current USB state or issue command:");
                pw.println("  ports");
                pw.println("  set-port-roles <id> <source|sink|no-power> <host|device|no-data>");
                pw.println("  add-port <id> <ufp|dfp|dual|none> <optional args>");
                pw.println("    <optional args> include:");
                pw.println("      --compliance-warnings: enables compliance warnings on port");
                pw.println("      --displayport: enables DisplayPort Alt Mode on port");
                pw.println("  connect-port <id> <ufp|dfp><?> <source|sink><?> <host|device><?>");
                pw.println("    (add ? suffix if mode, power role, or data role can be changed)");
                pw.println("  disconnect-port <id>");
                pw.println("  remove-port <id>");
                pw.println("  reset");
                pw.println();
                pw.println("Example USB type C port role switch:");
                pw.println("  dumpsys usb set-port-roles \"default\" source device");
                pw.println();
                pw.println("Example USB type C port simulation with full capabilities:");
                pw.println("  dumpsys usb add-port \"matrix\" dual --compliance-warnings "
                        + "--displayport");
                pw.println("  dumpsys usb connect-port \"matrix\" ufp? sink? device?");
                pw.println("  dumpsys usb ports");
                pw.println("  dumpsys usb disconnect-port \"matrix\"");
                pw.println("  dumpsys usb remove-port \"matrix\"");
                pw.println("  dumpsys usb reset");
                pw.println();
                pw.println("Example USB type C port where only power role can be changed:");
                pw.println("  dumpsys usb add-port \"matrix\" dual");
                pw.println("  dumpsys usb connect-port \"matrix\" dfp source? host");
                pw.println("  dumpsys usb reset");
                pw.println();
                pw.println("Example USB OTG port where id pin determines function:");
                pw.println("  dumpsys usb add-port \"matrix\" dual");
                pw.println("  dumpsys usb connect-port \"matrix\" dfp source host");
                pw.println("  dumpsys usb reset");
                pw.println();
                pw.println("Example USB device-only port:");
                pw.println("  dumpsys usb add-port \"matrix\" ufp");
                pw.println("  dumpsys usb connect-port \"matrix\" ufp sink device");
                pw.println("  dumpsys usb reset");
                pw.println();
                pw.println("Example simulate contaminant status:");
                pw.println("  dumpsys usb add-port \"matrix\" ufp");
                pw.println("  dumpsys usb set-contaminant-status \"matrix\" true");
                pw.println("  dumpsys usb set-contaminant-status \"matrix\" false");
                pw.println();
                pw.println("Example simulate compliance warnings:");
                pw.println("  dumpsys usb add-port \"matrix\" dual --compliance-warnings");
                pw.println("  dumpsys usb set-compliance-reasons \"matrix\" <reason-list>");
                pw.println("  dumpsys usb clear-compliance-reasons \"matrix\"");
                pw.println("<reason-list> is expected to be formatted as \"1, ..., N\"");
                pw.println("with reasons that need to be simulated.");
                pw.println("  1: other");
                pw.println("  2: debug accessory");
                pw.println("  3: bc12");
                pw.println("  4: missing rp");
                pw.println("  5: input power limited");
                pw.println("  6: missing data lines");
                pw.println("  7: enumeration fail");
                pw.println("  8: flaky connection");
                pw.println("  9: unreliable io");
                pw.println();
                pw.println("Example simulate DisplayPort Alt Mode Changes:");
                pw.println("  dumpsys usb add-port \"matrix\" dual --displayport");
                pw.println("  dumpsys usb set-displayport-status \"matrix\" <partner-sink>"
                        + " <cable> <num-lanes> <hpd> <link-training-status>");
                pw.println("The required fields are as followed:");
                pw.println("    <partner-sink>: type DisplayPortAltModeStatus");
                pw.println("    <cable>: type DisplayPortAltModeStatus");
                pw.println("    <num-lanes>: type int, expected 0, 2, or 4");
                pw.println("    <hpd>: type boolean, expected true or false");
                pw.println("    <link-training-status>: type LinkTrainingStatus");
                pw.println("  dumpsys usb reset-displayport-status \"matrix\"");
                pw.println("reset-displayport-status can also be used in order to set");
                pw.println("the DisplayPortInfo to default values.");
                pw.println();
                pw.println("Example enableUsbData");
                pw.println("This dumpsys command functions for both simulated and real ports.");
                pw.println("  dumpsys usb enable-usb-data \"matrix\" true");
                pw.println("  dumpsys usb enable-usb-data \"matrix\" false");
                pw.println();
                pw.println("Example USB device descriptors:");
                pw.println("  dumpsys usb dump-descriptors -dump-short");
                pw.println("  dumpsys usb dump-descriptors -dump-tree");
                pw.println("  dumpsys usb dump-descriptors -dump-list");
                pw.println("  dumpsys usb dump-descriptors -dump-raw");
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static String removeLastChar(String value) {
        return value.substring(0, value.length() - 1);
    }

    /**
     * Upon app removal, clear associated UIDs from the mUsbDisableRequesters list
     * and re-enable USB data signaling if no remaining apps require USB disabling.
     */
    private class PackageUninstallMonitor extends PackageMonitor {
        @Override
        public void onUidRemoved(int uid) {
            synchronized (mUsbDisableRequesters) {
                for (String portId : mUsbDisableRequesters.keySet()) {
                    ArraySet<Integer> disabledUid = mUsbDisableRequesters.get(portId);
                    if (disabledUid != null) {
                        disabledUid.remove(uid);
                        if (disabledUid.isEmpty()) {
                            enableUsbData(portId, true, PACKAGE_MONITOR_OPERATION_ID,
                                    new IUsbOperationInternal.Default());
                        }
                    }
                }
            }
        }
    }

    /**
     * Implements a callback within StrongAuthTracker to disable USB data signaling
     * when the device enters lockdown mode. This likely involves updating a state
     * that controls USB data behavior.
     */
    private class StrongAuthTracker extends LockPatternUtils.StrongAuthTracker {
        private boolean mLockdownModeStatus;

        StrongAuthTracker(Context context, Looper looper) {
            super(context, looper);
        }

        @Override
        public synchronized void onStrongAuthRequiredChanged(int userId) {

            boolean lockDownTriggeredByUser = (getStrongAuthForUser(userId)
                    & STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN) != 0;
            //if it goes into the same lockdown status, no change is needed
            if (mLockdownModeStatus == lockDownTriggeredByUser) {
                return;
            }
            mLockdownModeStatus = lockDownTriggeredByUser;
            for (UsbPort port: mPortManager.getPorts()) {
                enableUsbData(port.getId(), !lockDownTriggeredByUser, STRONG_AUTH_OPERATION_ID,
                        new IUsbOperationInternal.Default());
            }
        }
    }
}
