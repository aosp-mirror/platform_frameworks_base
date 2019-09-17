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
import android.hardware.usb.IUsbManager;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.usb.UsbServiceDumpProto;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * UsbService manages all USB related state, including both host and device support.
 * Host related events and calls are delegated to UsbHostManager, and device related
 * support is delegated to UsbDeviceManager.
 */
public class UsbService extends IUsbManager.Stub {

    public static class Lifecycle extends SystemService {
        private UsbService mUsbService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mUsbService = new UsbService(getContext());
            publishBinderService(Context.USB_SERVICE, mUsbService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mUsbService.systemReady();
            } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
                mUsbService.bootCompleted();
            }
        }

        @Override
        public void onSwitchUser(int newUserId) {
            mUsbService.onSwitchUser(newUserId);
        }

        @Override
        public void onStopUser(int userHandle) {
            mUsbService.onStopUser(UserHandle.of(userHandle));
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mUsbService.onUnlockUser(userHandle);
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

    /**
     * The user id of the current user. There might be several profiles (with separate user ids)
     * per user.
     */
    @GuardedBy("mLock")
    private @UserIdInt int mCurrentUserId;

    private final Object mLock = new Object();

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
        mContext.registerReceiver(receiver, filter, null, null);
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
                int user = UserHandle.getUserId(uid);

                long ident = clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        if (mUserManager.isSameProfileGroup(user, mCurrentUserId)) {
                            fd = mHostManager.openDevice(deviceName, getPermissionsForUser(user),
                                    packageName, uid);
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
            int user = UserHandle.getUserId(uid);

            long ident = clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mUserManager.isSameProfileGroup(user, mCurrentUserId)) {
                        return mDeviceManager.openAccessory(accessory, getPermissionsForUser(user),
                                uid);
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

    /* Returns a dup of the control file descriptor for the given function. */
    @Override
    public ParcelFileDescriptor getControlFd(long function) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_MTP, null);
        return mDeviceManager.getControlFd(function);
    }

    @Override
    public void setDevicePackage(UsbDevice device, String packageName, int userId) {
        device = Preconditions.checkNotNull(device);

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
        accessory = Preconditions.checkNotNull(accessory);

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
        device = Preconditions.checkNotNull(device);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        user = Preconditions.checkNotNull(user);

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
        accessory = Preconditions.checkNotNull(accessory);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        user = Preconditions.checkNotNull(user);

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
        device = Preconditions.checkNotNull(device);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        user = Preconditions.checkNotNull(user);

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
        accessory = Preconditions.checkNotNull(accessory);
        packageNames = Preconditions.checkArrayElementsNotNull(packageNames, "packageNames");
        user = Preconditions.checkNotNull(user);

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
        device = Preconditions.checkNotNull(device);
        user = Preconditions.checkNotNull(user);

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
        accessory = Preconditions.checkNotNull(accessory);
        user = Preconditions.checkNotNull(user);

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
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            return getPermissionsForUser(userId).hasPermission(device, packageName, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            return getPermissionsForUser(userId).hasPermission(accessory, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void requestDevicePermission(UsbDevice device, String packageName, PendingIntent pi) {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            getPermissionsForUser(userId).requestPermission(device, packageName, pi, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void requestAccessoryPermission(
            UsbAccessory accessory, String packageName, PendingIntent pi) {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            getPermissionsForUser(userId).requestPermission(accessory, packageName, pi, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void grantDevicePermission(UsbDevice device, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            getPermissionsForUser(userId).grantDevicePermission(device, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
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

    @Override
    public void setCurrentFunctions(long functions) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        Preconditions.checkArgument(UsbManager.areSettableFunctions(functions));
        Preconditions.checkState(mDeviceManager != null);
        mDeviceManager.setCurrentFunctions(functions);
    }

    @Override
    public void setCurrentFunction(String functions, boolean usbDataUnlocked) {
        setCurrentFunctions(UsbManager.usbFunctionsFromString(functions));
    }

    @Override
    public boolean isFunctionEnabled(String function) {
        return (getCurrentFunctions() & UsbManager.usbFunctionsFromString(function)) != 0;
    }

    @Override
    public long getCurrentFunctions() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        Preconditions.checkState(mDeviceManager != null);
        return mDeviceManager.getCurrentFunctions();
    }

    @Override
    public void setScreenUnlockedFunctions(long functions) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        Preconditions.checkArgument(UsbManager.areSettableFunctions(functions));
        Preconditions.checkState(mDeviceManager != null);

        mDeviceManager.setScreenUnlockedFunctions(functions);
    }

    @Override
    public long getScreenUnlockedFunctions() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        Preconditions.checkState(mDeviceManager != null);
        return mDeviceManager.getScreenUnlockedFunctions();
    }

    @Override
    public List<ParcelableUsbPort> getPorts() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

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
        Preconditions.checkNotNull(portId, "portId must not be null");
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            return mPortManager != null ? mPortManager.getPortStatus(portId) : null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setPortRoles(String portId, int powerRole, int dataRole) {
        Preconditions.checkNotNull(portId, "portId must not be null");
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
    public void enableContaminantDetection(String portId, boolean enable) {
        Preconditions.checkNotNull(portId, "portId must not be null");
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

    @Override
    public void setUsbDeviceConnectionHandler(ComponentName usbDeviceConnectionHandler) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
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
            } else if ("add-port".equals(args[0]) && args.length == 3) {
                final String portId = args[1];
                final int supportedModes;
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
                if (mPortManager != null) {
                    mPortManager.addSimulatedPort(portId, supportedModes, pw);
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
                pw.println("  add-port <id> <ufp|dfp|dual|none>");
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
                pw.println("  dumpsys usb add-port \"matrix\" dual");
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
}
