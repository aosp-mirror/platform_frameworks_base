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
import android.gsi.GsiProgress;
import android.gsi.IGsiService;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.image.IDynamicSystemService;
import android.util.Slog;

/**
 * DynamicSystemService implements IDynamicSystemService. It provides permission check before
 * passing requests to gsid
 */
public class DynamicSystemService extends IDynamicSystemService.Stub implements DeathRecipient {
    private static final String TAG = "DynamicSystemService";
    private static final String NO_SERVICE_ERROR = "no gsiservice";
    private static final int GSID_ROUGH_TIMEOUT_MS = 8192;

    private Context mContext;
    private volatile IGsiService mGsiService;

    DynamicSystemService(Context context) {
        mContext = context;
    }

    private static IGsiService connect(DeathRecipient recipient) throws RemoteException {
        IBinder binder = ServiceManager.getService("gsiservice");
        if (binder == null) {
            throw new RemoteException(NO_SERVICE_ERROR);
        }
        /**
         * The init will restart gsiservice if it crashed and the proxy object will need to be
         * re-initialized in this case.
         */
        binder.linkToDeath(recipient, 0);
        return IGsiService.Stub.asInterface(binder);
    }

    /** implements DeathRecipient */
    @Override
    public void binderDied() {
        Slog.w(TAG, "gsiservice died; reconnecting");
        synchronized (this) {
            mGsiService = null;
        }
    }

    private IGsiService getGsiService() throws RemoteException {
        checkPermission();
        if (!"running".equals(SystemProperties.get("init.svc.gsid"))) {
            SystemProperties.set("ctl.start", "gsid");
        }
        for (int sleepMs = 64; sleepMs <= (GSID_ROUGH_TIMEOUT_MS << 1); sleepMs <<= 1) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Slog.e(TAG, "Interrupted when waiting for GSID");
                break;
            }
            if ("running".equals(SystemProperties.get("init.svc.gsid"))) {
                synchronized (this) {
                    if (mGsiService == null) {
                        mGsiService = connect(this);
                    }
                    return mGsiService;
                }
            }
        }
        Slog.e(TAG, "Unable to start gsid");
        return null;
    }

    private void checkPermission() {
        if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires MANAGE_DYNAMIC_SYSTEM permission");
        }
    }

    @Override
    public boolean startInstallation(long systemSize, long userdataSize) throws RemoteException {
        return getGsiService().startGsiInstall(systemSize, userdataSize, true) == 0;
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
    public boolean isInUse() throws RemoteException {
        return getGsiService().isGsiRunning();
    }

    @Override
    public boolean isInstalled() throws RemoteException {
        return getGsiService().isGsiInstalled();
    }

    @Override
    public boolean remove() throws RemoteException {
        return getGsiService().removeGsiInstall();
    }

    @Override
    public boolean toggle() throws RemoteException {
        IGsiService gsiService = getGsiService();
        if (gsiService.isGsiRunning()) {
            return gsiService.disableGsiInstall();
        } else {
            final int status = gsiService.getGsiBootStatus();
            final boolean singleBoot = (status == IGsiService.BOOT_STATUS_SINGLE_BOOT);
            return gsiService.setGsiBootable(singleBoot) == 0;
        }
    }

    @Override
    public boolean write(byte[] buf) throws RemoteException {
        return getGsiService().commitGsiChunkFromMemory(buf);
    }

    @Override
    public boolean commit() throws RemoteException {
        return getGsiService().setGsiBootable(true) == 0;
    }
}
