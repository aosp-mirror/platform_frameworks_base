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

import android.content.Context;
import android.content.pm.PackageManager;
import android.gsi.AvbPublicKey;
import android.gsi.GsiProgress;
import android.gsi.IGsiService;
import android.gsi.IGsiServiceCallback;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.image.IDynamicSystemService;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Slog;

import java.io.File;

/**
 * DynamicSystemService implements IDynamicSystemService. It provides permission check before
 * passing requests to gsid
 */
public class DynamicSystemService extends IDynamicSystemService.Stub {
    private static final String TAG = "DynamicSystemService";
    private static final int GSID_ROUGH_TIMEOUT_MS = 8192;
    private static final String PATH_DEFAULT = "/data/gsi/";
    private Context mContext;
    private String mInstallPath, mDsuSlot;
    private volatile IGsiService mGsiService;

    DynamicSystemService(Context context) {
        mContext = context;
    }

    private IGsiService getGsiService() {
        checkPermission();
        if (mGsiService != null) {
            return mGsiService;
        }
        return IGsiService.Stub.asInterface(ServiceManager.waitForService("gsiservice"));
    }

    private void checkPermission() {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires MANAGE_DYNAMIC_SYSTEM permission");
        }
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
    public boolean startInstallation(String dsuSlot) throws RemoteException {
        IGsiService service = getGsiService();
        mGsiService = service;
        // priority from high to low: sysprop -> sdcard -> /data
        String path = SystemProperties.get("os.aot.path");
        if (path.isEmpty()) {
            final int userId = UserHandle.myUserId();
            final StorageVolume[] volumes =
                    StorageManager.getVolumeList(userId, StorageManager.FLAG_FOR_WRITE);
            for (StorageVolume volume : volumes) {
                if (volume.isEmulated()) continue;
                if (!volume.isRemovable()) continue;
                if (!Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                File sdCard = volume.getPathFile();
                if (sdCard.isDirectory()) {
                    path = new File(sdCard, dsuSlot).getPath();
                    break;
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
    public boolean createPartition(String name, long size, boolean readOnly)
            throws RemoteException {
        IGsiService service = getGsiService();
        if (service.createPartition(name, size, readOnly) != 0) {
            Slog.i(TAG, "Failed to install " + name);
            return false;
        }
        return true;
    }

    @Override
    public boolean finishInstallation() throws RemoteException {
        IGsiService service = getGsiService();
        if (service.closeInstall() != 0) {
            Slog.i(TAG, "Failed to finish installation");
            return false;
        }
        return true;
    }

    @Override
    public GsiProgress getInstallationProgress() throws RemoteException {
        return getGsiService().getInstallProgress();
    }

    @Override
    public boolean abort() throws RemoteException {
        return getGsiService().cancelGsiInstall();
    }

    @Override
    public boolean isInUse() {
        return SystemProperties.getBoolean("ro.gsid.image_running", false);
    }

    @Override
    public boolean isInstalled() {
        boolean installed = SystemProperties.getBoolean("gsid.image_installed", false);
        Slog.i(TAG, "isInstalled(): " + installed);
        return installed;
    }

    @Override
    public boolean isEnabled() throws RemoteException {
        return getGsiService().isGsiEnabled();
    }

    @Override
    public boolean remove() throws RemoteException {
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
    public boolean setEnable(boolean enable, boolean oneShot) throws RemoteException {
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
    public boolean setAshmem(ParcelFileDescriptor ashmem, long size) {
        try {
            return getGsiService().setGsiAshmem(ashmem, size);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    @Override
    public boolean submitFromAshmem(long size) {
        try {
            return getGsiService().commitGsiChunkFromAshmem(size);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    @Override
    public boolean getAvbPublicKey(AvbPublicKey dst) {
        try {
            return getGsiService().getAvbPublicKey(dst) == 0;
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }
}
