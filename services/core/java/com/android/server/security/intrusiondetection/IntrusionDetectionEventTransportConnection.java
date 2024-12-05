/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.intrusiondetection;

import static android.Manifest.permission.BIND_INTRUSION_DETECTION_EVENT_TRANSPORT_SERVICE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.security.intrusiondetection.IIntrusionDetectionEventTransport;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.infra.AndroidFuture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.Process;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

public class IntrusionDetectionEventTransportConnection implements ServiceConnection {
    private static final String PRODUCTION_BUILD = "user";
    private static final String PROPERTY_BUILD_TYPE = "ro.build.type";
    private static final String PROPERTY_INTRUSION_DETECTION_SERVICE_NAME =
            "debug.intrusiondetection_package_name";
    private static final long FUTURE_TIMEOUT_MILLIS = 60 * 1000; // 1 min
    private static final String TAG = "IntrusionDetectionEventTransportConnection";
    private final Context mContext;
    private String mIntrusionDetectionEventTransportConfig;
    volatile IIntrusionDetectionEventTransport mService;


    public IntrusionDetectionEventTransportConnection(Context context) {
        mContext = context;
    }

    /**
     * Initialize the IntrusionDetectionEventTransport binder service.
     *
     * @return Whether the initialization succeeds.
     */
    public boolean initialize() {
        Slog.d(TAG, "initialize");
        if (!bindService()) {
            return false;
        }
        // Wait for the service to be connected before calling initialize.
        waitForConnection();
        AndroidFuture<Boolean> resultFuture = new AndroidFuture<>();
        try {
            mService.initialize(resultFuture);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception", e);
            unbindService();
            return false;
        }
        Boolean result = getFutureResult(resultFuture);
        if (result != null && result == true) {
            return true;
        } else {
            unbindService();
            return false;
        }
    }

    private void waitForConnection() {
        synchronized (this) {
            while (mService == null) {
                Slog.d(TAG, "waiting for connection to service...");
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    /* never interrupted */
                }
            }
            Slog.d(TAG, "connected to service");
        }
    }

    /**
     * Add data to the IntrusionDetectionEventTransport binder service.
     * @param data List of IntrusionDetectionEvent.
     * @return Whether the data is added to the binder service.
     */
    public boolean addData(List<IntrusionDetectionEvent> data) {
        AndroidFuture<Boolean> resultFuture = new AndroidFuture<>();
        try {
            mService.addData(data, resultFuture);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception", e);
            return false;
        }
        Boolean result = getFutureResult(resultFuture);
        return result != null && result == true;
    }

    /**
     * Release the BackupTransport binder service.
     */
    public void release() {
        AndroidFuture<Boolean> resultFuture = new AndroidFuture<>();
        try {
            mService.release(resultFuture);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception", e);
        } finally {
            unbindService();
        }
    }

    private <T> T getFutureResult(AndroidFuture<T> future) {
        try {
            return future.get(FUTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException
                 | CancellationException e) {
            Slog.e(TAG, "Failed to get result from transport:", e);
            return null;
        }
    }

    private String getSystemPropertyValue(String propertyName) {
        String commandString = "getprop " + propertyName;
        try {
            Process process = Runtime.getRuntime().exec(commandString);
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            String propertyValue = reader.readLine();
            reader.close();
            return propertyValue;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to get system property value:", e);
            return null;
        }
    }

    private boolean bindService() {
        String buildType = getSystemPropertyValue(PROPERTY_BUILD_TYPE);
        mIntrusionDetectionEventTransportConfig =
                mContext.getString(
                        com.android.internal.R.string.config_intrusionDetectionEventTransport);

        // If the build type is not production, and a property value is set, use it instead.
        // This allows us to test the service with a different config.
        if (!buildType.equals(PRODUCTION_BUILD)
                && !TextUtils.isEmpty(
                        getSystemPropertyValue(PROPERTY_INTRUSION_DETECTION_SERVICE_NAME))) {
            mIntrusionDetectionEventTransportConfig =
                    getSystemPropertyValue(PROPERTY_INTRUSION_DETECTION_SERVICE_NAME);
        }
        Slog.d(
                TAG,
                "mIntrusionDetectionEventTransportConfig: "
                        + mIntrusionDetectionEventTransportConfig);

        if (TextUtils.isEmpty(mIntrusionDetectionEventTransportConfig)) {
            Slog.e(TAG, "Unable to find a valid config for the transport service");
            return false;
        }

        ComponentName serviceComponent =
                ComponentName.unflattenFromString(mIntrusionDetectionEventTransportConfig);
        if (serviceComponent == null) {
            Slog.e(TAG, "Can't get serviceComponent name");
            return false;
        }

        try {
            ServiceInfo serviceInfo = mContext.getPackageManager().getServiceInfo(serviceComponent,
                    0 /* flags */);
            if (!BIND_INTRUSION_DETECTION_EVENT_TRANSPORT_SERVICE.equals(serviceInfo.permission)) {
                Slog.e(TAG, serviceComponent.flattenToShortString()
                        + " is not declared with the permission "
                        + "\"" + BIND_INTRUSION_DETECTION_EVENT_TRANSPORT_SERVICE + "\"");
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Unable to find serviceComponent");
            return false;
        }

        Intent intent = new Intent().setComponent(serviceComponent);
        boolean result = mContext.bindServiceAsUser(
                intent, this, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
        if (!result) {
            unbindService();
        }
        return result;
    }

    private void unbindService() {
        mContext.unbindService(this);
        mService = null;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (this) {
            mService = IIntrusionDetectionEventTransport.Stub.asInterface(service);
            Slog.d(TAG, "connected to service");
            this.notifyAll();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        synchronized (this) {
            mService = null;
            Slog.d(TAG, "disconnected from service");
            this.notifyAll();
        }
    }
}
