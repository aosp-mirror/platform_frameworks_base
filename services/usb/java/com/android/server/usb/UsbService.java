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

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
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
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;

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
    }

    private static final String TAG = "UsbService";

    private final Context mContext;

    private UsbDeviceManager mDeviceManager;
    private UsbHostManager mHostManager;
    private UsbPortManager mPortManager;
    private final UsbAlsaManager mAlsaManager;

    private final Object mLock = new Object();

    /** Map from {@link UserHandle} to {@link UsbSettingsManager} */
    @GuardedBy("mLock")
    private final SparseArray<UsbSettingsManager>
            mSettingsByUser = new SparseArray<UsbSettingsManager>();

    private UsbSettingsManager getSettingsForUser(int userId) {
        synchronized (mLock) {
            UsbSettingsManager settings = mSettingsByUser.get(userId);
            if (settings == null) {
                settings = new UsbSettingsManager(mContext, new UserHandle(userId));
                mSettingsByUser.put(userId, settings);
            }
            return settings;
        }
    }

    public UsbService(Context context) {
        mContext = context;

        mAlsaManager = new UsbAlsaManager(context);

        final PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            mHostManager = new UsbHostManager(context, mAlsaManager);
        }
        if (new File("/sys/class/android_usb").exists()) {
            mDeviceManager = new UsbDeviceManager(context, mAlsaManager);
        }
        if (mHostManager != null || mDeviceManager != null) {
            mPortManager = new UsbPortManager(context);
        }

        setCurrentUser(UserHandle.USER_SYSTEM);

        final IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter, null, null);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            final String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                setCurrentUser(userId);
            } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                synchronized (mLock) {
                    mSettingsByUser.remove(userId);
                }
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                    .equals(action)) {
                if (mDeviceManager != null) {
                    mDeviceManager.updateUserRestrictions();
                }
            }
        }
    };

    private void setCurrentUser(int userId) {
        final UsbSettingsManager userSettings = getSettingsForUser(userId);
        if (mHostManager != null) {
            mHostManager.setCurrentSettings(userSettings);
        }
        if (mDeviceManager != null) {
            mDeviceManager.setCurrentUser(userId, userSettings);
        }
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

    /* Returns a list of all currently attached USB devices (host mdoe) */
    @Override
    public void getDeviceList(Bundle devices) {
        if (mHostManager != null) {
            mHostManager.getDeviceList(devices);
        }
    }

    /* Opens the specified USB device (host mode) */
    @Override
    public ParcelFileDescriptor openDevice(String deviceName) {
        if (mHostManager != null) {
            return mHostManager.openDevice(deviceName);
        } else {
            return null;
        }
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
            return mDeviceManager.openAccessory(accessory);
        } else {
            return null;
        }
    }

    @Override
    public void setDevicePackage(UsbDevice device, String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        getSettingsForUser(userId).setDevicePackage(device, packageName);
    }

    @Override
    public void setAccessoryPackage(UsbAccessory accessory, String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        getSettingsForUser(userId).setAccessoryPackage(accessory, packageName);
    }

    @Override
    public boolean hasDevicePermission(UsbDevice device) {
        final int userId = UserHandle.getCallingUserId();
        return getSettingsForUser(userId).hasPermission(device);
    }

    @Override
    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        final int userId = UserHandle.getCallingUserId();
        return getSettingsForUser(userId).hasPermission(accessory);
    }

    @Override
    public void requestDevicePermission(UsbDevice device, String packageName, PendingIntent pi) {
        final int userId = UserHandle.getCallingUserId();
        getSettingsForUser(userId).requestPermission(device, packageName, pi);
    }

    @Override
    public void requestAccessoryPermission(
            UsbAccessory accessory, String packageName, PendingIntent pi) {
        final int userId = UserHandle.getCallingUserId();
        getSettingsForUser(userId).requestPermission(accessory, packageName, pi);
    }

    @Override
    public void grantDevicePermission(UsbDevice device, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        final int userId = UserHandle.getUserId(uid);
        getSettingsForUser(userId).grantDevicePermission(device, uid);
    }

    @Override
    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        final int userId = UserHandle.getUserId(uid);
        getSettingsForUser(userId).grantAccessoryPermission(accessory, uid);
    }

    @Override
    public boolean hasDefaults(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        return getSettingsForUser(userId).hasDefaults(packageName);
    }

    @Override
    public void clearDefaults(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        getSettingsForUser(userId).clearDefaults(packageName);
    }

    @Override
    public boolean isFunctionEnabled(String function) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        return mDeviceManager != null && mDeviceManager.isFunctionEnabled(function);
    }

    @Override
    public void setCurrentFunction(String function) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        if (!isSupportedCurrentFunction(function)) {
            Slog.w(TAG, "Caller of setCurrentFunction() requested unsupported USB function: "
                    + function);
            function = UsbManager.USB_FUNCTION_NONE;
        }

        if (mDeviceManager != null) {
            mDeviceManager.setCurrentFunctions(function);
        } else {
            throw new IllegalStateException("USB device mode not supported");
        }
    }

    private static boolean isSupportedCurrentFunction(String function) {
        if (function == null) return true;

        switch (function) {
            case UsbManager.USB_FUNCTION_NONE:
            case UsbManager.USB_FUNCTION_AUDIO_SOURCE:
            case UsbManager.USB_FUNCTION_MIDI:
            case UsbManager.USB_FUNCTION_MTP:
            case UsbManager.USB_FUNCTION_PTP:
            case UsbManager.USB_FUNCTION_RNDIS:
                return true;
        }

        return false;
    }

    @Override
    public void setUsbDataUnlocked(boolean unlocked) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.setUsbDataUnlocked(unlocked);
    }

    @Override
    public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.allowUsbDebugging(alwaysAllow, publicKey);
    }

    @Override
    public void denyUsbDebugging() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.denyUsbDebugging();
    }

    @Override
    public void clearUsbDebuggingKeys() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.clearUsbDebuggingKeys();
    }

    @Override
    public UsbPort[] getPorts() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            return mPortManager != null ? mPortManager.getPorts() : null;
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
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        final long ident = Binder.clearCallingIdentity();
        try {
            if (args == null || args.length == 0 || "-a".equals(args[0])) {
                pw.println("USB Manager State:");
                pw.increaseIndent();
                if (mDeviceManager != null) {
                    mDeviceManager.dump(pw);
                }
                if (mHostManager != null) {
                    mHostManager.dump(pw);
                }
                if (mPortManager != null) {
                    mPortManager.dump(pw);
                }
                mAlsaManager.dump(pw);

                synchronized (mLock) {
                    for (int i = 0; i < mSettingsByUser.size(); i++) {
                        final int userId = mSettingsByUser.keyAt(i);
                        final UsbSettingsManager settings = mSettingsByUser.valueAt(i);
                        pw.println("Settings for user " + userId + ":");
                        pw.increaseIndent();
                        settings.dump(pw);
                        pw.decreaseIndent();
                    }
                }
            } else if (args.length == 4 && "set-port-roles".equals(args[0])) {
                final String portId = args[1];
                final int powerRole;
                switch (args[2]) {
                    case "source":
                        powerRole = UsbPort.POWER_ROLE_SOURCE;
                        break;
                    case "sink":
                        powerRole = UsbPort.POWER_ROLE_SINK;
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
                        dataRole = UsbPort.DATA_ROLE_HOST;
                        break;
                    case "device":
                        dataRole = UsbPort.DATA_ROLE_DEVICE;
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
                    mPortManager.dump(pw);
                }
            } else if (args.length == 3 && "add-port".equals(args[0])) {
                final String portId = args[1];
                final int supportedModes;
                switch (args[2]) {
                    case "ufp":
                        supportedModes = UsbPort.MODE_UFP;
                        break;
                    case "dfp":
                        supportedModes = UsbPort.MODE_DFP;
                        break;
                    case "dual":
                        supportedModes = UsbPort.MODE_DUAL;
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
                    mPortManager.dump(pw);
                }
            } else if (args.length == 5 && "connect-port".equals(args[0])) {
                final String portId = args[1];
                final int mode;
                final boolean canChangeMode = args[2].endsWith("?");
                switch (canChangeMode ? removeLastChar(args[2]) : args[2]) {
                    case "ufp":
                        mode = UsbPort.MODE_UFP;
                        break;
                    case "dfp":
                        mode = UsbPort.MODE_DFP;
                        break;
                    default:
                        pw.println("Invalid mode: " + args[2]);
                        return;
                }
                final int powerRole;
                final boolean canChangePowerRole = args[3].endsWith("?");
                switch (canChangePowerRole ? removeLastChar(args[3]) : args[3]) {
                    case "source":
                        powerRole = UsbPort.POWER_ROLE_SOURCE;
                        break;
                    case "sink":
                        powerRole = UsbPort.POWER_ROLE_SINK;
                        break;
                    default:
                        pw.println("Invalid power role: " + args[3]);
                        return;
                }
                final int dataRole;
                final boolean canChangeDataRole = args[4].endsWith("?");
                switch (canChangeDataRole ? removeLastChar(args[4]) : args[4]) {
                    case "host":
                        dataRole = UsbPort.DATA_ROLE_HOST;
                        break;
                    case "device":
                        dataRole = UsbPort.DATA_ROLE_DEVICE;
                        break;
                    default:
                        pw.println("Invalid data role: " + args[4]);
                        return;
                }
                if (mPortManager != null) {
                    mPortManager.connectSimulatedPort(portId, mode, canChangeMode,
                            powerRole, canChangePowerRole, dataRole, canChangeDataRole, pw);
                    pw.println();
                    mPortManager.dump(pw);
                }
            } else if (args.length == 2 && "disconnect-port".equals(args[0])) {
                final String portId = args[1];
                if (mPortManager != null) {
                    mPortManager.disconnectSimulatedPort(portId, pw);
                    pw.println();
                    mPortManager.dump(pw);
                }
            } else if (args.length == 2 && "remove-port".equals(args[0])) {
                final String portId = args[1];
                if (mPortManager != null) {
                    mPortManager.removeSimulatedPort(portId, pw);
                    pw.println();
                    mPortManager.dump(pw);
                }
            } else if (args.length == 1 && "reset".equals(args[0])) {
                if (mPortManager != null) {
                    mPortManager.resetSimulation(pw);
                    pw.println();
                    mPortManager.dump(pw);
                }
            } else if (args.length == 1 && "ports".equals(args[0])) {
                if (mPortManager != null) {
                    mPortManager.dump(pw);
                }
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
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static final String removeLastChar(String value) {
        return value.substring(0, value.length() - 1);
    }
}
