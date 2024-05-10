/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.remotelockscreenvalidation;

import static android.service.remotelockscreenvalidation.RemoteLockscreenValidationService.SERVICE_INTERFACE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Implements {@link RemoteLockscreenValidationClient}.
 *
 * @hide
 */
public class RemoteLockscreenValidationClientImpl implements RemoteLockscreenValidationClient,
        ServiceConnection {

    private static final String TAG = RemoteLockscreenValidationClientImpl.class.getSimpleName();
    private final Handler mHandler;
    private final Context mContext;
    private final Queue<Call> mRequestQueue;
    private final Executor mLifecycleExecutor;
    private final boolean mIsServiceAvailable;
    private boolean mIsConnected;

    @Nullable
    private IRemoteLockscreenValidationService mService;

    @Nullable
    private ServiceInfo mServiceInfo;

    RemoteLockscreenValidationClientImpl(
            @NonNull Context context,
            @Nullable Executor bgExecutor,
            @NonNull ComponentName serviceComponent) {
        mContext = context.getApplicationContext();
        mIsServiceAvailable = isServiceAvailable(mContext, serviceComponent);
        mHandler = new Handler(Looper.getMainLooper());
        mLifecycleExecutor = (bgExecutor == null) ? Runnable::run : bgExecutor;
        mRequestQueue = new ArrayDeque<>();
    }

    @Override
    public boolean isServiceAvailable() {
        return mIsServiceAvailable;
    }

    @Override
    public void validateLockscreenGuess(
            byte[] guess, IRemoteLockscreenValidationCallback callback) {
        try {
            if (!isServiceAvailable()) {
                callback.onFailure("Service is not available");
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error while failing for service unavailable", e);
        }

        executeApiCall(new Call() {
            @Override
            public void exec(IRemoteLockscreenValidationService service) throws RemoteException {
                service.validateLockscreenGuess(guess, callback);
            }

            @Override
            void onError(String msg) {
                try {
                    callback.onFailure(msg);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while failing validateLockscreenGuess", e);
                }
            }
        });
    }

    @Override
    public void disconnect() {
        mHandler.post(this::disconnectInternal);
    }

    private void disconnectInternal() {
        if (!mIsConnected) {
            Log.w(TAG, "already disconnected");
            return;
        }
        mIsConnected = false;
        mLifecycleExecutor.execute(() -> mContext.unbindService(/* conn= */ this));
        mService = null;
        mRequestQueue.clear();
    }

    private void connect() {
        mHandler.post(this::connectInternal);
    }

    private void connectInternal() {
        if (mServiceInfo == null) {
            Log.w(TAG, "RemoteLockscreenValidation service unavailable");
            return;
        }
        if (mIsConnected) {
            return;
        }
        mIsConnected = true;
        Intent intent = new Intent(SERVICE_INTERFACE);
        intent.setComponent(mServiceInfo.getComponentName());
        int flags = Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY;
        mLifecycleExecutor.execute(() -> mContext.bindService(intent, this, flags));
    }

    private void onConnectedInternal(IRemoteLockscreenValidationService service) {
        if (!mIsConnected) {
            Log.w(TAG, "onConnectInternal but connection closed");
            mService = null;
            return;
        }
        mService = service;
        for (Call call : new ArrayList<>(mRequestQueue)) {
            performApiCallInternal(call, mService);
            mRequestQueue.remove(call);
        }
    }

    private boolean isServiceAvailable(
            @NonNull Context context,
            @NonNull ComponentName serviceComponent) {
        mServiceInfo = getServiceInfo(context, serviceComponent);
        if (mServiceInfo == null) {
            return false;
        }

        if (!Manifest.permission.BIND_REMOTE_LOCKSCREEN_VALIDATION_SERVICE.equals(
                mServiceInfo.permission)) {
            Log.w(TAG, TextUtils.formatSimple("%s/%s does not require permission %s",
                    mServiceInfo.packageName, mServiceInfo.name,
                    Manifest.permission.BIND_REMOTE_LOCKSCREEN_VALIDATION_SERVICE));
            return false;
        }
        return true;
    }

    private ServiceInfo getServiceInfo(
            @NonNull Context context, @NonNull ComponentName serviceComponent) {
        try {
            return context.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA));
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, TextUtils.formatSimple("Cannot resolve service %s",
                    serviceComponent.getClassName()));
            return null;
        }
    }

    private void executeApiCall(Call call) {
        mHandler.post(() -> executeInternal(call));
    }

    private void executeInternal(RemoteLockscreenValidationClientImpl.Call call) {
        if (mIsConnected && mService != null) {
            performApiCallInternal(call, mService);
        } else {
            mRequestQueue.add(call);
            connect();
        }
    }

    private void performApiCallInternal(
            RemoteLockscreenValidationClientImpl.Call apiCaller,
            IRemoteLockscreenValidationService service) {
        if (service == null) {
            apiCaller.onError("Service is null");
            return;
        }
        try {
            apiCaller.exec(service);
        } catch (RemoteException e) {
            Log.w(TAG, "executeInternal error", e);
            apiCaller.onError(e.getMessage());
            disconnect();
        }
    }

    @Override // ServiceConnection
    public void onServiceConnected(ComponentName name, IBinder binder) {
        IRemoteLockscreenValidationService service =
                IRemoteLockscreenValidationService.Stub.asInterface(binder);
        mHandler.post(() -> onConnectedInternal(service));
    }

    @Override // ServiceConnection
    public void onServiceDisconnected(ComponentName name) {
        // Do not disconnect, as we may later be re-connected
    }

    @Override // ServiceConnection
    public void onBindingDied(ComponentName name) {
        // This is a recoverable error but the client will need to reconnect.
        disconnect();
    }

    @Override // ServiceConnection
    public void onNullBinding(ComponentName name) {
        disconnect();
    }

    private abstract static class Call {
        abstract void exec(IRemoteLockscreenValidationService service)
                throws RemoteException;
        abstract void onError(String msg);
    }
}
