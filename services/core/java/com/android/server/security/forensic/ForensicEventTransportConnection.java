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

package com.android.server.security.forensic;

import static android.Manifest.permission.BIND_FORENSIC_EVENT_TRANSPORT_SERVICE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.forensic.ForensicEvent;
import android.security.forensic.IForensicEventTransport;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ForensicEventTransportConnection implements ServiceConnection {
    private static final String TAG = "ForensicEventTransportConnection";
    private static final long FUTURE_TIMEOUT_MILLIS = 60 * 1000; // 1 mins
    private final Context mContext;
    private String mForensicEventTransportConfig;
    volatile IForensicEventTransport mService;

    public ForensicEventTransportConnection(Context context) {
        mContext = context;
        mService = null;
    }

    /**
     * Initialize the ForensicEventTransport binder service.
     * @return Whether the initialization succeed.
     */
    public boolean initialize() {
        if (!bindService()) {
            return false;
        }
        AndroidFuture<Integer> resultFuture = new AndroidFuture<>();
        try {
            mService.initialize(resultFuture);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception", e);
            unbindService();
            return false;
        }
        Integer result = getFutureResult(resultFuture);
        if (result != null && result == 0) {
            return true;
        } else {
            unbindService();
            return false;
        }
    }

    /**
     * Add data to the ForensicEventTransport binder service.
     * @param data List of ForensicEvent.
     * @return Whether the data is added to the binder service.
     */
    public boolean addData(List<ForensicEvent> data) {
        AndroidFuture<Integer> resultFuture = new AndroidFuture<>();
        try {
            mService.addData(data, resultFuture);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception", e);
            return false;
        }
        Integer result = getFutureResult(resultFuture);
        return result != null && result == 0;
    }

    /**
     * Release the BackupTransport binder service.
     */
    public void release() {
        AndroidFuture<Integer> resultFuture = new AndroidFuture<>();
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

    private boolean bindService() {
        mForensicEventTransportConfig = mContext.getString(
                com.android.internal.R.string.config_forensicEventTransport);
        if (TextUtils.isEmpty(mForensicEventTransportConfig)) {
            Slog.e(TAG, "config_forensicEventTransport is empty");
            return false;
        }

        ComponentName serviceComponent =
                ComponentName.unflattenFromString(mForensicEventTransportConfig);
        if (serviceComponent == null) {
            Slog.e(TAG, "Can't get serviceComponent name");
            return false;
        }

        try {
            ServiceInfo serviceInfo = mContext.getPackageManager().getServiceInfo(serviceComponent,
                    0 /* flags */);
            if (!BIND_FORENSIC_EVENT_TRANSPORT_SERVICE.equals(serviceInfo.permission)) {
                Slog.e(TAG, serviceComponent.flattenToShortString()
                        + " is not declared with the permission "
                        + "\"" + BIND_FORENSIC_EVENT_TRANSPORT_SERVICE + "\"");
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
        mService = IForensicEventTransport.Stub.asInterface(service);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mService = null;
    }
}
