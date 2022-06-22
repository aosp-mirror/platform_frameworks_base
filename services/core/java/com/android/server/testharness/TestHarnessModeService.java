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
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.debug.AdbManagerInternal;
import android.location.LocationManager;
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

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.PersistentDataBlockManagerInternal;
import com.android.server.SystemService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static final String TEST_HARNESS_MODE_PROPERTY = "persist.sys.test_harness";
    private static final String TAG = TestHarnessModeService.class.getSimpleName();

    private PersistentDataBlockManagerInternal mPersistentDataBlockManagerInternal;

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
                completeTestHarnessModeSetup();
                showNotificationIfEnabled();
                break;
        }
        super.onBootPhase(phase);
    }

    /**
     * Begin the setup for Test Harness Mode.
     *
     * <p>Note: This is just the things that <em>need</em> to be done before the device finishes
     * booting for the first time. Everything else should be done after the system is done booting.
     */
    private void setUpTestHarnessMode() {
        Slog.d(TAG, "Setting up test harness mode");
        byte[] testHarnessModeData = getTestHarnessModeData();
        if (testHarnessModeData == null) {
            return;
        }
        // If there is data, we should set the device as provisioned, so that we skip the setup
        // wizard.
        setDeviceProvisioned();
        disableLockScreen();
        SystemProperties.set(TEST_HARNESS_MODE_PROPERTY, "1");
    }

    private void disableLockScreen() {
        UserInfo userInfo = getPrimaryUser();
        LockPatternUtils utils = new LockPatternUtils(getContext());
        utils.setLockScreenDisabled(true, userInfo.id);
    }

    private void completeTestHarnessModeSetup() {
        Slog.d(TAG, "Completing Test Harness Mode setup.");
        byte[] testHarnessModeData = getTestHarnessModeData();
        if (testHarnessModeData == null) {
            return;
        }
        try {
            setUpAdbFiles(PersistentData.fromBytes(testHarnessModeData));
            configureSettings();
            configureUser();
        } catch (SetUpTestHarnessModeException e) {
            Slog.e(TAG, "Failed to set up Test Harness Mode. Bad data.", e);
        } finally {
            // Clear out the Test Harness Mode data so that we don't repeat the setup. If it failed
            // to set up, then retrying without enabling Test Harness Mode should allow it to boot.
            // If we succeeded setting up, we shouldn't be re-applying the THM steps every boot
            // anyway.
            getPersistentDataBlock().clearTestHarnessModeData();
        }
    }

    private byte[] getTestHarnessModeData() {
        PersistentDataBlockManagerInternal blockManager = getPersistentDataBlock();
        if (blockManager == null) {
            Slog.e(TAG, "Failed to start Test Harness Mode; no implementation of "
                    + "PersistentDataBlockManagerInternal was bound!");
            return null;
        }
        byte[] testHarnessModeData = blockManager.getTestHarnessModeData();
        if (testHarnessModeData == null || testHarnessModeData.length == 0) {
            // There's no data to apply, so leave it as-is.
            return null;
        }
        return testHarnessModeData;
    }

    private void configureSettings() {
        ContentResolver cr = getContext().getContentResolver();

        // If adb is already enabled, then we need to restart the daemon to pick up the change in
        // keys. This is only really useful for userdebug/eng builds.
        if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1) {
            SystemProperties.set("ctl.restart", "adbd");
            Slog.d(TAG, "Restarted adbd");
        }

        // Disable the TTL for ADB keys before ADB is enabled as a part of AdbService's
        // initialization.
        Settings.Global.putLong(cr, Settings.Global.ADB_ALLOWED_CONNECTION_TIME, 0);
        Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
        Settings.Global.putInt(cr, Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, 0);
        Settings.Global.putInt(
                cr,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                BatteryManager.BATTERY_PLUGGED_ANY);
        Settings.Global.putInt(cr, Settings.Global.OTA_DISABLE_AUTOMATIC_UPDATE, 1);
    }

    private void setUpAdbFiles(PersistentData persistentData) {
        AdbManagerInternal adbManager = LocalServices.getService(AdbManagerInternal.class);

        if (adbManager.getAdbKeysFile() != null) {
            writeBytesToFile(persistentData.mAdbKeys, adbManager.getAdbKeysFile().toPath());
        }
        if (adbManager.getAdbTempKeysFile() != null) {
            writeBytesToFile(persistentData.mAdbTempKeys, adbManager.getAdbTempKeysFile().toPath());
        }
        adbManager.notifyKeyFilesUpdated();
    }

    private void configureUser() {
        UserInfo primaryUser = getPrimaryUser();

        ContentResolver.setMasterSyncAutomaticallyAsUser(false, primaryUser.id);

        LocationManager locationManager = getContext().getSystemService(LocationManager.class);
        locationManager.setLocationEnabledForUser(true, primaryUser.getUserHandle());
    }

    private UserInfo getPrimaryUser() {
        UserManager userManager = UserManager.get(getContext());
        return userManager.getPrimaryUser();
    }

    private void writeBytesToFile(byte[] keys, Path adbKeys) {
        try {
            OutputStream fileOutputStream = Files.newOutputStream(adbKeys);
            fileOutputStream.write(keys);
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

    private void showNotificationIfEnabled() {
        if (!SystemProperties.getBoolean(TEST_HARNESS_MODE_PROPERTY, false)) {
            return;
        }
        String title = getContext()
                .getString(com.android.internal.R.string.test_harness_mode_notification_title);
        String message = getContext()
                .getString(com.android.internal.R.string.test_harness_mode_notification_message);

        Notification notification =
                new Notification.Builder(getContext(), SystemNotificationChannels.DEVELOPER)
                        .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                        .setWhen(0)
                        .setOngoing(true)
                        .setTicker(title)
                        .setDefaults(0)  // please be quiet
                        .setColor(getContext().getColor(
                                com.android.internal.R.color
                                        .system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(message)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build();

        NotificationManager notificationManager =
                getContext().getSystemService(NotificationManager.class);
        notificationManager.notifyAsUser(
                null, SystemMessage.NOTE_TEST_HARNESS_MODE_ENABLED, notification, UserHandle.ALL);
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
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
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
            KeyguardManager keyguardManager = getContext().getSystemService(KeyguardManager.class);
            return keyguardManager.isDeviceSecure(getPrimaryUser().id);
        }

        private int handleEnable() {
            AdbManagerInternal adbManager = LocalServices.getService(AdbManagerInternal.class);
            File adbKeys = adbManager.getAdbKeysFile();
            File adbTempKeys = adbManager.getAdbTempKeysFile();

            try {
                byte[] adbKeysBytes = getBytesFromFile(adbKeys);
                byte[] adbTempKeysBytes = getBytesFromFile(adbTempKeys);

                PersistentData persistentData = new PersistentData(adbKeysBytes, adbTempKeysBytes);
                PersistentDataBlockManagerInternal blockManager = getPersistentDataBlock();
                if (blockManager == null) {
                    Slog.e(TAG, "Failed to enable Test Harness Mode. No implementation of "
                            + "PersistentDataBlockManagerInternal was bound.");
                    getErrPrintWriter().println("Failed to enable Test Harness Mode");
                    return 1;
                }
                blockManager.setTestHarnessModeData(persistentData.toBytes());
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

        private byte[] getBytesFromFile(File file) throws IOException {
            if (file == null || !file.exists()) {
                return new byte[0];
            }
            Path path = file.toPath();
            try (InputStream inputStream = Files.newInputStream(path)) {
                int size = (int) Files.size(path);
                byte[] bytes = new byte[size];
                int numBytes = inputStream.read(bytes);
                if (numBytes != size) {
                    throw new IOException("Failed to read the whole file");
                }
                return bytes;
            }
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
        static final byte VERSION_2 = 2;

        final int mVersion;
        final byte[] mAdbKeys;
        final byte[] mAdbTempKeys;

        PersistentData(byte[] adbKeys, byte[] adbTempKeys) {
            this(VERSION_2, adbKeys, adbTempKeys);
        }

        PersistentData(int version, byte[] adbKeys, byte[] adbTempKeys) {
            this.mVersion = version;
            this.mAdbKeys = adbKeys;
            this.mAdbTempKeys = adbTempKeys;
        }

        static PersistentData fromBytes(byte[] bytes) throws SetUpTestHarnessModeException {
            try {
                DataInputStream is = new DataInputStream(new ByteArrayInputStream(bytes));
                int version = is.readInt();
                if (version == VERSION_1) {
                    // Version 1 of Test Harness Mode contained an "enabled" bit that we need to
                    // skip. If we don't, the binary format will be bad and it will fail to set up.
                    is.readBoolean();
                }
                int adbKeysLength = is.readInt();
                byte[] adbKeys = new byte[adbKeysLength];
                is.readFully(adbKeys);
                int adbTempKeysLength = is.readInt();
                byte[] adbTempKeys = new byte[adbTempKeysLength];
                is.readFully(adbTempKeys);
                return new PersistentData(version, adbKeys, adbTempKeys);
            } catch (IOException e) {
                throw new SetUpTestHarnessModeException(e);
            }
        }

        byte[] toBytes() {
            try {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(os);
                dos.writeInt(VERSION_2);
                dos.writeInt(mAdbKeys.length);
                dos.write(mAdbKeys);
                dos.writeInt(mAdbTempKeys.length);
                dos.write(mAdbTempKeys);
                dos.close();
                return os.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * An exception thrown when Test Harness Mode fails to set up.
     *
     * <p>In the event that Test Harness Mode fails to set up, all of the data should be discarded
     * and the Test Harness Mode portion of the persistent data block should be wiped. This will
     * prevent the device from becoming stuck, as there is no way (without rooting the device) to
     * clear the persistent data block.
     */
    private static class SetUpTestHarnessModeException extends Exception {
        SetUpTestHarnessModeException(Exception e) {
            super(e);
        }
    }
}
