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
import android.os.IDynamicAndroidService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

/**
 * DynamicAndroidService implements IDynamicAndroidService. It provides permission check before
 * passing requests to gsid
 */
public class DynamicAndroidService extends IDynamicAndroidService.Stub implements DeathRecipient {
    private static final String TAG = "DynamicAndroidService";
    private static final String NO_SERVICE_ERROR = "no gsiservice";

    private Context mContext;
    private volatile IGsiService mGsiService;

    DynamicAndroidService(Context context) {
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
        synchronized (this) {
            if (mGsiService == null) {
                mGsiService = connect(this);
            }
            return mGsiService;
        }
    }

    private void checkPermission() {
        if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_DYNAMIC_ANDROID)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires MANAGE_DYNAMIC_ANDROID permission");
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
            return gsiService.setGsiBootable() == 0;
        }
    }

    @Override
    public boolean write(byte[] buf) throws RemoteException {
        return getGsiService().commitGsiChunkFromMemory(buf);
    }

    @Override
    public boolean commit() throws RemoteException {
        return getGsiService().setGsiBootable() == 0;
    }
}
