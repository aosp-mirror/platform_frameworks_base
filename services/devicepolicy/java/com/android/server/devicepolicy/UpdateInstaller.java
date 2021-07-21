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

package com.android.server.devicepolicy;

import android.annotation.Nullable;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager.InstallSystemUpdateCallback;
import android.app.admin.StartInstallingUpdateCallback;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

abstract class UpdateInstaller {
    private StartInstallingUpdateCallback mCallback;
    private ParcelFileDescriptor mUpdateFileDescriptor;
    private DevicePolicyConstants mConstants;
    protected Context mContext;

    @Nullable protected File mCopiedUpdateFile;

    static final String TAG = "UpdateInstaller";
    private DevicePolicyManagerService.Injector mInjector;

    protected UpdateInstaller(Context context, ParcelFileDescriptor updateFileDescriptor,
            StartInstallingUpdateCallback callback, DevicePolicyManagerService.Injector injector,
            DevicePolicyConstants constants) {
        mContext = context;
        mCallback = callback;
        mUpdateFileDescriptor = updateFileDescriptor;
        mInjector = injector;
        mConstants = constants;
    }

    public abstract void installUpdateInThread();

    public void startInstallUpdate() {
        mCopiedUpdateFile = null;
        if (!isBatteryLevelSufficient()) {
            notifyCallbackOnError(
                    InstallSystemUpdateCallback.UPDATE_ERROR_BATTERY_LOW,
                    "The battery level must be above "
                            + mConstants.BATTERY_THRESHOLD_NOT_CHARGING + " while not charging or "
                            + "above " + mConstants.BATTERY_THRESHOLD_CHARGING + " while charging");
            return;
        }
        Thread thread = new Thread(() -> {
            mCopiedUpdateFile = copyUpdateFileToDataOtaPackageDir();
            if (mCopiedUpdateFile == null) {
                notifyCallbackOnError(
                        InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN,
                        "Error while copying file.");
                return;
            }
            installUpdateInThread();
        });
        thread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
    }

    private boolean isBatteryLevelSufficient() {
        Intent batteryStatus = mContext.registerReceiver(
                /* receiver= */ null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        float batteryPercentage = calculateBatteryPercentage(batteryStatus);
        boolean isBatteryPluggedIn =
                batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, /* defaultValue= */ -1) > 0;
        return isBatteryPluggedIn
                ? batteryPercentage >= mConstants.BATTERY_THRESHOLD_CHARGING
                : batteryPercentage >= mConstants.BATTERY_THRESHOLD_NOT_CHARGING;
    }

    private float calculateBatteryPercentage(Intent batteryStatus) {
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, /* defaultValue= */ -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, /* defaultValue= */ -1);
        return 100 * level / (float) scale;
    }

    private File copyUpdateFileToDataOtaPackageDir() {
        try {
            File destination = createNewFileWithPermissions();
            copyToFile(destination);
            return destination;
        } catch (IOException e) {
            Log.w(TAG, "Failed to copy update file to OTA directory", e);
            notifyCallbackOnError(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN,
                    Log.getStackTraceString(e));
            return null;
        }
    }

    private File createNewFileWithPermissions() throws IOException {
        File destination = File.createTempFile(
                "update", ".zip", new File(Environment.getDataDirectory() + "/ota_package"));
        FileUtils.setPermissions(
                /* path= */ destination,
                /* mode= */ FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IROTH,
                /* uid= */ -1, /* gid= */ -1);
        return destination;
    }

    private void copyToFile(File destination) throws IOException {
        try (OutputStream out = new FileOutputStream(destination);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                     mUpdateFileDescriptor)) {
            FileUtils.copy(in, out);
        }
    }

    void cleanupUpdateFile() {
        if (mCopiedUpdateFile != null && mCopiedUpdateFile.exists()) {
            mCopiedUpdateFile.delete();
        }
    }

    protected void notifyCallbackOnError(int errorCode, String errorMessage) {
        cleanupUpdateFile();
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.INSTALL_SYSTEM_UPDATE_ERROR)
                .setInt(errorCode)
                .write();
        try {
            mCallback.onStartInstallingUpdateError(errorCode, errorMessage);
        } catch (RemoteException e) {
            Log.d(TAG, "Error while calling callback", e);
        }
    }

    protected void notifyCallbackOnSuccess() {
        cleanupUpdateFile();
        mInjector.powerManagerReboot(PowerManager.REBOOT_REQUESTED_BY_DEVICE_OWNER);
    }
}
