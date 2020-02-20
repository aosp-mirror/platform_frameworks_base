/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.adb;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.debug.AdbManagerInternal;
import android.debug.AdbTransportType;
import android.debug.IAdbManager;
import android.debug.IAdbTransport;
import android.debug.PairDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.adb.AdbServiceDumpProto;
import android.sysprop.AdbProperties;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;

/**
 * The Android Debug Bridge (ADB) service. This controls the availability of ADB and authorization
 * of devices allowed to connect to ADB.
 */
public class AdbService extends IAdbManager.Stub {
    /**
     * Manages the service lifecycle for {@code AdbService} in {@code SystemServer}.
     */
    public static class Lifecycle extends SystemService {
        private AdbService mAdbService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mAdbService = new AdbService(getContext());
            publishBinderService(Context.ADB_SERVICE, mAdbService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mAdbService.systemReady();
            } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
                FgThread.getHandler().sendMessage(obtainMessage(
                        AdbService::bootCompleted, mAdbService));
            }
        }
    }

    private class AdbManagerInternalImpl extends AdbManagerInternal {
        @Override
        public void registerTransport(IAdbTransport transport) {
            mTransports.put(transport.asBinder(), transport);
        }

        @Override
        public void unregisterTransport(IAdbTransport transport) {
            mTransports.remove(transport.asBinder());
        }

        @Override
        public boolean isAdbEnabled(byte transportType) {
            if (transportType == AdbTransportType.USB) {
                return mIsAdbUsbEnabled;
            } else if (transportType == AdbTransportType.WIFI) {
                return mIsAdbWifiEnabled;
            }
            throw new IllegalArgumentException(
                    "isAdbEnabled called with unimplemented transport type=" + transportType);
        }

        @Override
        public File getAdbKeysFile() {
            return mDebuggingManager.getUserKeyFile();
        }

        @Override
        public File getAdbTempKeysFile() {
            return mDebuggingManager.getAdbTempKeysFile();
        }
    }

    private void initAdbState() {
        try {
            /*
             * Use the normal bootmode persistent prop to maintain state of adb across
             * all boot modes.
             */
            mIsAdbUsbEnabled = containsFunction(
                    SystemProperties.get(USB_PERSISTENT_CONFIG_PROPERTY, ""),
                    UsbManager.USB_FUNCTION_ADB);
            // TODO(joshuaduong): Read the adb wifi state from a persistent system
            // property (persist.sys.adb.wifi).
            mIsAdbWifiEnabled = false;

            // register observer to listen for settings changes
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                    false, new AdbSettingsObserver());
        } catch (Exception e) {
            Slog.e(TAG, "Error in initAdbState", e);
        }
    }

    private static boolean containsFunction(String functions, String function) {
        int index = functions.indexOf(function);
        if (index < 0) return false;
        if (index > 0 && functions.charAt(index - 1) != ',') return false;
        int charAfter = index + function.length();
        if (charAfter < functions.length() && functions.charAt(charAfter) != ',') return false;
        return true;
    }

    private class AdbSettingsObserver extends ContentObserver {
        private final Uri mAdbUsbUri = Settings.Global.getUriFor(Settings.Global.ADB_ENABLED);

        AdbSettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange, @NonNull Uri uri, @UserIdInt int userId) {
            if (mAdbUsbUri.equals(uri)) {
                boolean shouldEnable = (Settings.Global.getInt(mContentResolver,
                        Settings.Global.ADB_ENABLED, 0) > 0);
                FgThread.getHandler().sendMessage(obtainMessage(
                        AdbService::setAdbEnabled, AdbService.this, shouldEnable,
                            AdbTransportType.USB));
            }
            // TODO(joshuaduong): Add condition for WIFI transport
        }
    }

    private static final String TAG = "AdbService";
    private static final boolean DEBUG = false;

    /**
     * The persistent property which stores whether adb is enabled or not.
     * May also contain vendor-specific default functions for testing purposes.
     */
    private static final String USB_PERSISTENT_CONFIG_PROPERTY = "persist.sys.usb.config";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final ArrayMap<IBinder, IAdbTransport> mTransports = new ArrayMap<>();

    private boolean mIsAdbUsbEnabled;
    private boolean mIsAdbWifiEnabled;
    private AdbDebuggingManager mDebuggingManager;

    private AdbService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        boolean secureAdbEnabled = AdbProperties.secure().orElse(false);
        boolean dataEncrypted = "1".equals(SystemProperties.get("vold.decrypt"));
        if (secureAdbEnabled && !dataEncrypted) {
            mDebuggingManager = new AdbDebuggingManager(context);
        }

        initAdbState();
        LocalServices.addService(AdbManagerInternal.class, new AdbManagerInternalImpl());
    }

    /**
     * Called in response to {@code SystemService.PHASE_ACTIVITY_MANAGER_READY} from {@code
     * SystemServer}.
     */
    public void systemReady() {
        if (DEBUG) Slog.d(TAG, "systemReady");

        // make sure the ADB_ENABLED setting value matches the current state
        try {
            Settings.Global.putInt(mContentResolver,
                    Settings.Global.ADB_ENABLED, mIsAdbUsbEnabled ? 1 : 0);
        } catch (SecurityException e) {
            // If UserManager.DISALLOW_DEBUGGING_FEATURES is on, that this setting can't be changed.
            Slog.d(TAG, "ADB_ENABLED is restricted.");
        }
    }

    /**
     * Callend in response to {@code SystemService.PHASE_BOOT_COMPLETED} from {@code SystemServer}.
     */
    public void bootCompleted() {
        if (DEBUG) Slog.d(TAG, "boot completed");
        if (mDebuggingManager != null) {
            mDebuggingManager.setAdbEnabled(mIsAdbUsbEnabled, AdbTransportType.USB);
            mDebuggingManager.setAdbEnabled(mIsAdbWifiEnabled, AdbTransportType.WIFI);
        }
    }

    @Override
    public void allowDebugging(boolean alwaysAllow, String publicKey) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        if (mDebuggingManager != null) {
            mDebuggingManager.allowDebugging(alwaysAllow, publicKey);
        }
    }

    @Override
    public void denyDebugging() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        if (mDebuggingManager != null) {
            mDebuggingManager.denyDebugging();
        }
    }

    @Override
    public void clearDebuggingKeys() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        if (mDebuggingManager != null) {
            mDebuggingManager.clearDebuggingKeys();
        } else {
            throw new RuntimeException("Cannot clear ADB debugging keys, "
                    + "AdbDebuggingManager not enabled");
        }
    }

    /**
     * @return true if the device supports secure ADB over Wi-Fi.
     * @hide
     */
    @Override
    public boolean isAdbWifiSupported() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.MANAGE_DEBUGGING, "AdbService");
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
    }

    /**
     * @return true if the device supports secure ADB over Wi-Fi and device pairing by
     * QR code.
     * @hide
     */
    @Override
    public boolean isAdbWifiQrSupported() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.MANAGE_DEBUGGING, "AdbService");
        return isAdbWifiSupported() && mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_CAMERA_ANY);
    }

    @Override
    public void allowWirelessDebugging(boolean alwaysAllow, String bssid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
    }

    @Override
    public void denyWirelessDebugging() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
    }

    @Override
    public Map<String, PairDevice> getPairedDevices() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
        return null;
    }

    @Override
    public void unpairDevice(String fingerprint) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
    }

    @Override
    public void enablePairingByPairingCode() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
    }

    @Override
    public void enablePairingByQrCode(String serviceName, String password) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
    }

    @Override
    public void disablePairing() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
    }

    @Override
    public int getAdbWirelessPort() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING, null);
        // TODO(joshuaduong): NOT IMPLEMENTED
        return 0;
    }

    private void setAdbEnabled(boolean enable, byte transportType) {
        if (DEBUG) {
            Slog.d(TAG, "setAdbEnabled(" + enable + "), mIsAdbUsbEnabled=" + mIsAdbUsbEnabled
                    + ", mIsAdbWifiEnabled=" + mIsAdbWifiEnabled + ", transportType="
                        + transportType);
        }

        if (transportType == AdbTransportType.USB && enable != mIsAdbUsbEnabled) {
            mIsAdbUsbEnabled = enable;
        } else if (transportType == AdbTransportType.WIFI && enable != mIsAdbWifiEnabled) {
            mIsAdbWifiEnabled = enable;
        } else {
            // No change
            return;
        }

        for (IAdbTransport transport : mTransports.values()) {
            try {
                transport.onAdbEnabled(enable, transportType);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to send onAdbEnabled to transport " + transport.toString());
            }
        }

        if (mDebuggingManager != null) {
            mDebuggingManager.setAdbEnabled(enable, transportType);
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

            if (argsSet.size() == 0 || argsSet.contains("-a") || dumpAsProto) {
                DualDumpOutputStream dump;
                if (dumpAsProto) {
                    dump = new DualDumpOutputStream(new ProtoOutputStream(fd));
                } else {
                    pw.println("ADB MANAGER STATE (dumpsys adb):");

                    dump = new DualDumpOutputStream(new IndentingPrintWriter(pw, "  "));
                }

                if (mDebuggingManager != null) {
                    mDebuggingManager.dump(dump, "debugging_manager",
                            AdbServiceDumpProto.DEBUGGING_MANAGER);
                }

                dump.flush();
            } else {
                pw.println("Dump current ADB state");
                pw.println("  No commands available");
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
