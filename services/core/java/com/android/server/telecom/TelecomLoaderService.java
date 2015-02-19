/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.SystemService;

/**
 * Starts the telecom component by binding to its ITelecomService implementation. Telecom is setup
 * to run in the system-server process so once it is loaded into memory it will stay running.
 * @hide
 */
public class TelecomLoaderService extends SystemService {
    private static final String TAG = "TelecomLoaderService";

    private class TelecomServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Normally, we would listen for death here, but since telecom runs in the same process
            // as this loader (process="system") thats redundant here.
            try {
                service.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        connectToTelecom();
                    }
                }, 0);

                ServiceManager.addService(Context.TELECOM_SERVICE, service);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed linking to death.");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            connectToTelecom();
        }
    }

    private static final ComponentName SERVICE_COMPONENT = new ComponentName(
            "com.android.server.telecom",
            "com.android.server.telecom.TelecomService");

    private static final String SERVICE_ACTION = "com.android.ITelecomService";

    private final Context mContext;
    private TelecomServiceConnection mServiceConnection;

    public TelecomLoaderService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            connectToTelecom();
        }
    }

    private void connectToTelecom() {
        if (mServiceConnection != null) {
            // TODO: Is unbinding worth doing or wait for system to rebind?
            mContext.unbindService(mServiceConnection);
            mServiceConnection = null;
        }

        TelecomServiceConnection serviceConnection = new TelecomServiceConnection();
        Intent intent = new Intent(SERVICE_ACTION);
        intent.setComponent(SERVICE_COMPONENT);
        int flags = Context.BIND_IMPORTANT | Context.BIND_AUTO_CREATE;

        // Bind to Telecom and register the service
        if (mContext.bindServiceAsUser(intent, serviceConnection, flags, UserHandle.OWNER)) {
            mServiceConnection = serviceConnection;
        }
    }
}
