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
package com.android.servicestests.apps.simpleservicetestapp;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

public class SimpleService extends Service {
    private static final String TAG = "SimpleService";

    private static final String TEST_CLASS =
            "com.android.servicestests.apps.simpleservicetestapp.SimpleService";

    private static final String ACTION_SERVICE_WITH_DEP_PKG =
            "com.android.servicestests.apps.simpleservicetestapp.ACTION_SERVICE_WITH_DEP_PKG";

    private static final String EXTRA_CALLBACK = "callback";
    private static final String EXTRA_COMMAND = "command";
    private static final String EXTRA_FLAGS = "flags";
    private static final String EXTRA_TARGET_PACKAGE = "target_package";

    private static final int COMMAND_INVALID = 0;
    private static final int COMMAND_EMPTY = 1;
    private static final int COMMAND_BIND_SERVICE = 2;
    private static final int COMMAND_UNBIND_SERVICE = 3;
    private static final int COMMAND_STOP_SELF = 4;

    private ArrayMap<String, ServiceConnection> mServiceConnections = new ArrayMap<>();

    private final IRemoteCallback.Stub mBinder = new IRemoteCallback.Stub() {
        @Override
        public void sendResult(Bundle bundle) {
            if (bundle == null) {
                Process.killProcess(Process.myPid());
            } else {
                // No-op for now.
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        if (intent == null) {
            return START_STICKY;
        }
        int command = intent.getIntExtra(EXTRA_COMMAND, COMMAND_INVALID);
        if (command != COMMAND_INVALID) {
            final String targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
            Log.i(TAG, "Received command " + command + " targetPkg=" + targetPkg);
            switch (command) {
                case COMMAND_BIND_SERVICE:
                    final Bundle extras = intent.getExtras();
                    bindToService(targetPkg, intent.getIntExtra(EXTRA_FLAGS, 0),
                            IRemoteCallback.Stub.asInterface(extras.getBinder(EXTRA_CALLBACK)));
                    break;
                case COMMAND_UNBIND_SERVICE:
                    unbindService(targetPkg);
                    break;
                case COMMAND_STOP_SELF:
                    stopSelf();
                    return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    private void bindToService(String targetPkg, int flags, IRemoteCallback callback) {
        Intent intent = new Intent();
        intent.setClassName(targetPkg, TEST_CLASS);
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (callback != null) {
                    try {
                        callback.sendResult(new Bundle());
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        if (getApplicationContext().bindService(intent, conn, BIND_AUTO_CREATE | flags)) {
            mServiceConnections.put(targetPkg, conn);
        } else if (callback != null) {
            try {
                callback.sendResult(null);
            } catch (RemoteException e) {
            }
        }
    }

    private void unbindService(String targetPkg) {
        final ServiceConnection conn = mServiceConnections.remove(targetPkg);
        if (conn != null) {
            getApplicationContext().unbindService(conn);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (ACTION_SERVICE_WITH_DEP_PKG.equals(intent.getAction())) {
            final String targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
            Log.i(TAG, "SimpleService.onBind: " + ACTION_SERVICE_WITH_DEP_PKG + " " + targetPkg);
            if (targetPkg != null) {
                Context pkgContext = null;
                try {
                    pkgContext = createPackageContext(targetPkg,
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Unable to create package context for " + pkgContext, e);
                }
                // This effectively loads the target package as a dependency.
                pkgContext.getClassLoader();
            }
        }
        return mBinder;
    }
}
