/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.testharness;

import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.PersistentDataBlockManagerInternal;
import com.android.server.SystemService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Manages the Test Harness Mode service for setting up test harness mode on the device.
 *
 * <p>Test Harness Mode is a feature that allows the user to clean their device, retain ADB keys,
 * and provision the device for Instrumentation testing. This means that all parts of the device
 * that would otherwise interfere with testing (auto-syncing accounts, package verification,
 * automatic updates, etc.) are all disabled by default but may be re-enabled by the user.
 */
public class TestHarnessModeService extends SystemService {
    private static final String TAG = TestHarnessModeService.class.getSimpleName();
    private static final String TEST_HARNESS_MODE_PROPERTY = "persist.sys.test_harness";

    private PersistentDataBlockManagerInternal mPersistentDataBlockManagerInternal;
    private boolean mShouldSetUpTestHarnessMode;

    public TestHarnessModeService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService("testharness", mService);
    }

    @Override
    public void onBootPhase(int phase) {
        switch (phase) {
            case PHASE_SYSTEM_SERVICES_READY:
                setUpTestHarnessMode();
                break;
            case PHASE_BOOT_COMPLETED:
                disableAutoSync();
                break;
        }
        super.onBootPhase(phase);
    }

    private void setUpTestHarnessMode() {
        Slog.d(TAG, "Setting up test harness mode");
        byte[] testHarnessModeData = getPersistentDataBlock().getTestHarnessModeData();
        if (testHarnessModeData == null || testHarnessModeData.length == 0) {
            // There's no data to apply, so leave it as-is.
            return;
        }
        mShouldSetUpTestHarnessMode = true;
        PersistentData persistentData = PersistentData.fromBytes(testHarnessModeData);

        SystemProperties.set(TEST_HARNESS_MODE_PROPERTY, persistentData.mEnabled ? "1" : "0");
        writeAdbKeysFile(persistentData);
        // Clear out the data block so that we don't revert the ADB keys on every boot.
        getPersistentDataBlock().clearTestHarnessModeData();

        ContentResolver cr = getContext().getContentResolver();
        if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0) {
            // Enable ADB
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
        } else {
            // ADB is already enabled, we should restart the service so it picks up the new keys
            android.os.SystemService.restart("adbd");
        }

        Settings.Global.putInt(cr, Settings.Global.PACKAGE_VERIFIER_ENABLE, 0);
        Settings.Global.putInt(
                cr,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                BatteryManager.BATTERY_PLUGGED_ANY);
        Settings.Global.putInt(cr, Settings.Global.OTA_DISABLE_AUTOMATIC_UPDATE, 1);
        Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);

        setDeviceProvisioned();
    }

    private void disableAutoSync() {
        if (!mShouldSetUpTestHarnessMode) {
            return;
        }
        UserInfo primaryUser = UserManager.get(getContext()).getPrimaryUser();
        ContentResolver
            .setMasterSyncAutomaticallyAsUser(false, primaryUser.getUserHandle().getIdentifier());
    }

    private void writeAdbKeysFile(PersistentData persistentData) {
        Path adbKeys = Paths.get("/data/misc/adb/adb_keys");
        try {
            OutputStream fileOutputStream = Files.newOutputStream(adbKeys);
            fileOutputStream.write(persistentData.mAdbKeys);
            fileOutputStream.close();

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(adbKeys);
            permissions.add(PosixFilePermission.GROUP_READ);
            Files.setPosixFilePermissions(adbKeys, permissions);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to set up adb keys", e);
            // Note: if a device enters this block, it will remain UNAUTHORIZED in ADB, but all
            // other settings will be set up.
        }
    }

    // Setting the device as provisioned skips the setup wizard.
    private void setDeviceProvisioned() {
        ContentResolver cr = getContext().getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putIntForUser(
                cr,
                Settings.Secure.USER_SETUP_COMPLETE,
                1,
                UserHandle.USER_CURRENT);
    }

    @Nullable
    private PersistentDataBlockManagerInternal getPersistentDataBlock() {
        if (mPersistentDataBlockManagerInternal == null) {
            Slog.d(TAG, "Getting PersistentDataBlockManagerInternal from LocalServices");
            mPersistentDataBlockManagerInternal =
                    LocalServices.getService(PersistentDataBlockManagerInternal.class);
        }
        return mPersistentDataBlockManagerInternal;
    }

    private final IBinder mService = new Binder() {
        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new TestHarnessModeShellCommand())
                .exec(this, in, out, err, args, callback, resultReceiver);
        }
    };

    private class TestHarnessModeShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            switch (cmd) {
                case "enable":
                case "restore":
                    checkPermissions();
                    final long originalId = Binder.clearCallingIdentity();
                    try {
                        if (isDeviceSecure()) {
                            getErrPrintWriter().println(
                                    "Test Harness Mode cannot be enabled if there is a lock "
                                            + "screen");
                            return 2;
                        }
                        return handleEnable();
                    } finally {
                        Binder.restoreCallingIdentity(originalId);
                    }
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        private void checkPermissions() {
            getContext().enforceCallingPermission(
                    android.Manifest.permission.ENABLE_TEST_HARNESS_MODE,
                    "You must hold android.permission.ENABLE_TEST_HARNESS_MODE "
                            + "to enable Test Harness Mode");
        }

        private boolean isDeviceSecure() {
            UserInfo primaryUser = UserManager.get(getContext()).getPrimaryUser();
            KeyguardManager keyguardManager = getContext().getSystemService(KeyguardManager.class);
            return keyguardManager.isDeviceSecure(primaryUser.id);
        }

        private int handleEnable() {
            Path adbKeys = Paths.get("/data/misc/adb/adb_keys");
            if (!Files.exists(adbKeys)) {
                // This should only be accessible on eng builds that haven't yet set up ADB keys
                getErrPrintWriter()
                    .println("No ADB keys stored; not enabling test harness mode");
                return 1;
            }

            try (InputStream inputStream = Files.newInputStream(adbKeys)) {
                long size = Files.size(adbKeys);
                byte[] adbKeysBytes = new byte[(int) size];
                int numBytes = inputStream.read(adbKeysBytes);
                if (numBytes != size) {
                    getErrPrintWriter().println("Failed to read all bytes of adb_keys");
                    return 1;
                }
                PersistentData persistentData = new PersistentData(true, adbKeysBytes);
                getPersistentDataBlock().setTestHarnessModeData(persistentData.toBytes());
            } catch (IOException e) {
                Slog.e(TAG, "Failed to store ADB keys.", e);
                getErrPrintWriter().println("Failed to enable Test Harness Mode");
                return 1;
            }

            Intent i = new Intent(Intent.ACTION_FACTORY_RESET);
            i.setPackage("android");
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            i.putExtra(Intent.EXTRA_REASON, TAG);
            i.putExtra(Intent.EXTRA_WIPE_EXTERNAL_STORAGE, true);
            getContext().sendBroadcastAsUser(i, UserHandle.SYSTEM);
            return 0;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("About:");
            pw.println("  Test Harness Mode is a mode that the device can be placed in to prepare");
            pw.println("  the device for running UI tests. The device is placed into this mode by");
            pw.println("  first wiping all data from the device, preserving ADB keys.");
            pw.println();
            pw.println("  By default, the following settings are configured:");
            pw.println("    * Package Verifier is disabled");
            pw.println("    * Stay Awake While Charging is enabled");
            pw.println("    * OTA Updates are disabled");
            pw.println("    * Auto-Sync for accounts is disabled");
            pw.println();
            pw.println("  Other apps may configure themselves differently in Test Harness Mode by");
            pw.println("  checking ActivityManager.isRunningInUserTestHarness()");
            pw.println();
            pw.println("Test Harness Mode commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println();
            pw.println("  enable|restore");
            pw.println("    Erase all data from this device and enable Test Harness Mode,");
            pw.println("    preserving the stored ADB keys currently on the device and toggling");
            pw.println("    settings in a way that are conducive to Instrumentation testing.");
        }
    }

    /**
     * The object that will serialize/deserialize the Test Harness Mode data to and from the
     * persistent data block.
     */
    public static class PersistentData {
        static final byte VERSION_1 = 1;

        final int mVersion;
        final boolean mEnabled;
        final byte[] mAdbKeys;

        PersistentData(boolean enabled, byte[] adbKeys) {
            this(VERSION_1, enabled, adbKeys);
        }

        PersistentData(int version, boolean enabled, byte[] adbKeys) {
            this.mVersion = version;
            this.mEnabled = enabled;
            this.mAdbKeys = adbKeys;
        }

        static PersistentData fromBytes(byte[] bytes) {
            try {
                DataInputStream is = new DataInputStream(new ByteArrayInputStream(bytes));
                int version = is.readInt();
                boolean enabled = is.readBoolean();
                int adbKeysLength = is.readInt();
                byte[] adbKeys = new byte[adbKeysLength];
                is.readFully(adbKeys);
                return new PersistentData(version, enabled, adbKeys);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        byte[] toBytes() {
            try {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(os);
                dos.writeInt(VERSION_1);
                dos.writeBoolean(mEnabled);
                dos.writeInt(mAdbKeys.length);
                dos.write(mAdbKeys);
                dos.close();
                return os.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
