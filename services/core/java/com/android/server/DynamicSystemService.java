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

package com.android.server;

import android.annotation.EnforcePermission;
import android.annotation.RequiresNoPermission;
import android.content.Context;
import android.gsi.AvbPublicKey;
import android.gsi.GsiProgress;
import android.gsi.IGsiService;
import android.gsi.IGsiServiceCallback;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.image.IDynamicSystemService;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Slog;

import java.io.File;

/**
 * DynamicSystemService implements IDynamicSystemService. It provides permission check before
 * passing requests to gsid
 */
public class DynamicSystemService extends IDynamicSystemService.Stub {
    private static final String TAG = "DynamicSystemService";
    private static final long MINIMUM_SD_MB = (30L << 10);
    private static final int GSID_ROUGH_TIMEOUT_MS = 8192;
    private static final String PATH_DEFAULT = "/data/gsi/dsu/";
    private Context mContext;
    private String mInstallPath, mDsuSlot;
    private volatile IGsiService mGsiService;

    DynamicSystemService(Context context) {
        mContext = context;
    }

    private IGsiService getGsiService() {
        if (mGsiService != null) {
            return mGsiService;
        }
        return IGsiService.Stub.asInterface(ServiceManager.waitForService("gsiservice"));
    }

    class GsiServiceCallback extends IGsiServiceCallback.Stub {
        // 0 for success
        private int mResult = -1;

        public synchronized void onResult(int result) {
            mResult = result;
            notify();
        }

        public int getResult() {
            return mResult;
        }
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean startInstallation(String dsuSlot) throws RemoteException {
        super.startInstallation_enforcePermission();

        IGsiService service = getGsiService();
        mGsiService = service;
        // priority from high to low: sysprop -> sdcard -> /data
        String path = SystemProperties.get("os.aot.path");
        if (path.isEmpty()) {
            final int userId = UserHandle.myUserId();
            final StorageManager sm = mContext.getSystemService(StorageManager.class);
            for (VolumeInfo volume : sm.getVolumes()) {
                if (volume.getType() != volume.TYPE_PUBLIC) {
                    continue;
                }
                if (!volume.isMountedWritable()) {
                    continue;
                }
                // gsid only supports vfat external storage.
                if (!"vfat".equalsIgnoreCase(volume.fsType)) {
                    continue;
                }
                DiskInfo disk = volume.getDisk();
                long mega = disk.size >> 20;
                Slog.i(TAG, volume.getPath() + ": " + mega + " MB");
                if (mega < MINIMUM_SD_MB) {
                    Slog.i(TAG, volume.getPath() + ": insufficient storage");
                    continue;
                }
                File sd_internal = volume.getInternalPathForUser(userId);
                if (sd_internal != null) {
                    path = new File(sd_internal, dsuSlot).getPath();
                }
            }
            if (path.isEmpty()) {
                path = PATH_DEFAULT + dsuSlot;
            }
            Slog.i(TAG, "startInstallation -> " + path);
        }
        mInstallPath = path;
        mDsuSlot = dsuSlot;
        if (service.openInstall(path) != 0) {
            Slog.i(TAG, "Failed to open " + path);
            return false;
        }
        return true;
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public int createPartition(String name, long size, boolean readOnly) throws RemoteException {
        super.createPartition_enforcePermission();

        IGsiService service = getGsiService();
        int status = service.createPartition(name, size, readOnly);
        if (status != IGsiService.INSTALL_OK) {
            Slog.i(TAG, "Failed to create partition: " + name);
        }
        return status;
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean closePartition() throws RemoteException {
        super.closePartition_enforcePermission();

        IGsiService service = getGsiService();
        if (service.closePartition() != 0) {
            Slog.i(TAG, "Partition installation completes with error");
            return false;
        }
        return true;
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean finishInstallation() throws RemoteException {
        super.finishInstallation_enforcePermission();

        IGsiService service = getGsiService();
        if (service.closeInstall() != 0) {
            Slog.i(TAG, "Failed to finish installation");
            return false;
        }
        return true;
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public GsiProgress getInstallationProgress() throws RemoteException {
        super.getInstallationProgress_enforcePermission();

        return getGsiService().getInstallProgress();
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean abort() throws RemoteException {
        super.abort_enforcePermission();

        return getGsiService().cancelGsiInstall();
    }

    @Override
    @RequiresNoPermission
    public boolean isInUse() {
        return SystemProperties.getBoolean("ro.gsid.image_running", false);
    }

    @Override
    @RequiresNoPermission
    public boolean isInstalled() {
        boolean installed = SystemProperties.getBoolean("gsid.image_installed", false);
        Slog.i(TAG, "isInstalled(): " + installed);
        return installed;
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean isEnabled() throws RemoteException {
        super.isEnabled_enforcePermission();

        return getGsiService().isGsiEnabled();
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean remove() throws RemoteException {
        super.remove_enforcePermission();

        try {
            GsiServiceCallback callback = new GsiServiceCallback();
            synchronized (callback) {
                getGsiService().removeGsiAsync(callback);
                callback.wait(GSID_ROUGH_TIMEOUT_MS);
            }
            return callback.getResult() == 0;
        } catch (InterruptedException e) {
            Slog.e(TAG, "remove() was interrupted");
            return false;
        }
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean setEnable(boolean enable, boolean oneShot) throws RemoteException {
        super.setEnable_enforcePermission();

        IGsiService gsiService = getGsiService();
        if (enable) {
            try {
                if (mDsuSlot == null) {
                    mDsuSlot = gsiService.getActiveDsuSlot();
                }
                GsiServiceCallback callback = new GsiServiceCallback();
                synchronized (callback) {
                    gsiService.enableGsiAsync(oneShot, mDsuSlot, callback);
                    callback.wait(GSID_ROUGH_TIMEOUT_MS);
                }
                return callback.getResult() == 0;
            } catch (InterruptedException e) {
                Slog.e(TAG, "setEnable() was interrupted");
                return false;
            }
        } else {
            return gsiService.disableGsi();
        }
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean setAshmem(ParcelFileDescriptor ashmem, long size) {
        super.setAshmem_enforcePermission();

        try {
            return getGsiService().setGsiAshmem(ashmem, size);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean submitFromAshmem(long size) {
        super.submitFromAshmem_enforcePermission();

        try {
            return getGsiService().commitGsiChunkFromAshmem(size);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean getAvbPublicKey(AvbPublicKey dst) {
        super.getAvbPublicKey_enforcePermission();

        try {
            return getGsiService().getAvbPublicKey(dst) == 0;
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    @Override
    @EnforcePermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public long suggestScratchSize() throws RemoteException {
        super.suggestScratchSize_enforcePermission();

        return getGsiService().suggestScratchSize();
    }
}
