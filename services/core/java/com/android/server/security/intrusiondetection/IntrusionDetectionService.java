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

import static android.Manifest.permission.MANAGE_INTRUSION_DETECTION_STATE;
import static android.Manifest.permission.READ_INTRUSION_DETECTION_STATE;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.security.intrusiondetection.IIntrusionDetectionService;
import android.security.intrusiondetection.IIntrusionDetectionServiceCommandCallback;
import android.security.intrusiondetection.IIntrusionDetectionServiceStateCallback;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
public class IntrusionDetectionService extends SystemService {
    private static final String TAG = "IntrusionDetectionService";

    private static final int MAX_STATE_CALLBACK_NUM = 16;
    private static final int MSG_ADD_STATE_CALLBACK = 0;
    private static final int MSG_REMOVE_STATE_CALLBACK = 1;
    private static final int MSG_ENABLE = 2;
    private static final int MSG_DISABLE = 3;
    private static final int MSG_TRANSPORT = 4;

    private static final int STATE_UNKNOWN =
            IIntrusionDetectionServiceStateCallback.State.UNKNOWN;
    private static final int STATE_DISABLED =
            IIntrusionDetectionServiceStateCallback.State.DISABLED;
    private static final int STATE_ENABLED =
            IIntrusionDetectionServiceStateCallback.State.ENABLED;

    private static final int ERROR_UNKNOWN =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.UNKNOWN;
    private static final int ERROR_PERMISSION_DENIED =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.PERMISSION_DENIED;
    private static final int ERROR_INVALID_STATE_TRANSITION =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.INVALID_STATE_TRANSITION;
    private static final int ERROR_TRANSPORT_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.TRANSPORT_UNAVAILABLE;
    private static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;

    private final Context mContext;
    private final Handler mHandler;
    private final IntrusionDetectionEventTransportConnection
            mIntrusionDetectionEventTransportConnection;
    private final DataAggregator mDataAggregator;
    private final BinderService mBinderService;

    private final ArrayList<IIntrusionDetectionServiceStateCallback> mStateCallbacks =
            new ArrayList<>();
    private volatile int mState = STATE_DISABLED;

    public IntrusionDetectionService(@NonNull Context context) {
        this(new InjectorImpl(context));
    }

    @VisibleForTesting
    IntrusionDetectionService(@NonNull Injector injector) {
        super(injector.getContext());
        mContext = injector.getContext();
        mHandler = new EventHandler(injector.getLooper(), this);
        mIntrusionDetectionEventTransportConnection =
                injector.getIntrusionDetectionEventransportConnection();
        mDataAggregator = injector.getDataAggregator(this);
        mBinderService = new BinderService(this, injector.getPermissionEnforcer());
    }

    @VisibleForTesting
    protected void setState(int state) {
        mState = state;
    }

    private static final class BinderService extends IIntrusionDetectionService.Stub {
        final IntrusionDetectionService mService;

        BinderService(IntrusionDetectionService service,
                @NonNull PermissionEnforcer permissionEnforcer)  {
            super(permissionEnforcer);
            mService = service;
        }

        @Override
        @EnforcePermission(READ_INTRUSION_DETECTION_STATE)
        public void addStateCallback(IIntrusionDetectionServiceStateCallback callback) {
            addStateCallback_enforcePermission();
            mService.mHandler.obtainMessage(MSG_ADD_STATE_CALLBACK, callback).sendToTarget();
        }

        @Override
        @EnforcePermission(READ_INTRUSION_DETECTION_STATE)
        public void removeStateCallback(IIntrusionDetectionServiceStateCallback callback) {
            removeStateCallback_enforcePermission();
            mService.mHandler.obtainMessage(MSG_REMOVE_STATE_CALLBACK, callback).sendToTarget();
        }

        @Override
        @EnforcePermission(MANAGE_INTRUSION_DETECTION_STATE)
        public void enable(IIntrusionDetectionServiceCommandCallback callback) {
            enable_enforcePermission();
            mService.mHandler.obtainMessage(MSG_ENABLE, callback).sendToTarget();
        }

        @Override
        @EnforcePermission(MANAGE_INTRUSION_DETECTION_STATE)
        public void disable(IIntrusionDetectionServiceCommandCallback callback) {
            disable_enforcePermission();
            mService.mHandler.obtainMessage(MSG_DISABLE, callback).sendToTarget();
        }
    }

    private static class EventHandler extends Handler {
        private final IntrusionDetectionService mService;

        EventHandler(Looper looper, IntrusionDetectionService service) {
            super(looper);
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_STATE_CALLBACK:
                    try {
                        mService.addStateCallback(
                                (IIntrusionDetectionServiceStateCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_REMOVE_STATE_CALLBACK:
                    try {
                        mService.removeStateCallback(
                                (IIntrusionDetectionServiceStateCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_ENABLE:
                    try {
                        mService.enable((IIntrusionDetectionServiceCommandCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_DISABLE:
                    try {
                        mService.disable((IIntrusionDetectionServiceCommandCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_TRANSPORT:
                    mService.transport((List<IntrusionDetectionEvent>) msg.obj);
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }

    private void addStateCallback(IIntrusionDetectionServiceStateCallback callback)
            throws RemoteException {
        for (int i = 0; i < mStateCallbacks.size(); i++) {
            if (mStateCallbacks.get(i).asBinder() == callback.asBinder()) {
                return;
            }
        }
        mStateCallbacks.add(callback);
        callback.onStateChange(mState);
    }

    private void removeStateCallback(IIntrusionDetectionServiceStateCallback callback)
            throws RemoteException {
        for (int i = 0; i < mStateCallbacks.size(); i++) {
            if (mStateCallbacks.get(i).asBinder() == callback.asBinder()) {
                mStateCallbacks.remove(i);
                return;
            }
        }
    }

    private void notifyStateMonitors() {
        if (mStateCallbacks.size() >= MAX_STATE_CALLBACK_NUM) {
            mStateCallbacks.removeFirst();
        }

        for (int i = 0; i < mStateCallbacks.size(); i++) {
            try {
                mStateCallbacks.get(i).onStateChange(mState);
            } catch (RemoteException e) {
                mStateCallbacks.remove(i);
            }
        }
    }

    private void enable(IIntrusionDetectionServiceCommandCallback callback)
            throws RemoteException {
        if (mState == STATE_ENABLED) {
            callback.onSuccess();
            return;
        }

        if (!mIntrusionDetectionEventTransportConnection.initialize()) {
            callback.onFailure(ERROR_TRANSPORT_UNAVAILABLE);
            return;
        }

        mDataAggregator.enable();
        mState = STATE_ENABLED;
        notifyStateMonitors();
        callback.onSuccess();
    }

    private void disable(IIntrusionDetectionServiceCommandCallback callback)
            throws RemoteException {
        if (mState == STATE_DISABLED) {
            callback.onSuccess();
            return;
        }

        mIntrusionDetectionEventTransportConnection.release();
        mDataAggregator.disable();
        mState = STATE_DISABLED;
        notifyStateMonitors();
        callback.onSuccess();
    }

    /**
     * Add a list of IntrusionDetectionEvent.
     */
    public void addNewData(List<IntrusionDetectionEvent> events) {
        mHandler.obtainMessage(MSG_TRANSPORT, events).sendToTarget();
    }

    private void transport(List<IntrusionDetectionEvent> events) {
        mIntrusionDetectionEventTransportConnection.addData(events);
    }

    @Override
    public void onStart() {
        try {
            publishBinderService(Context.INTRUSION_DETECTION_SERVICE, mBinderService);
        } catch (Throwable t) {
            Slog.e(TAG, "Could not start the IntrusionDetectionService.", t);
        }
    }

    @VisibleForTesting
    IIntrusionDetectionService getBinderService() {
        return mBinderService;
    }

    interface Injector {
        Context getContext();

        PermissionEnforcer getPermissionEnforcer();

        Looper getLooper();

        IntrusionDetectionEventTransportConnection getIntrusionDetectionEventransportConnection();

        DataAggregator getDataAggregator(IntrusionDetectionService intrusionDetectionService);
    }

    private static final class InjectorImpl implements Injector {
        private final Context mContext;

        InjectorImpl(Context context) {
            mContext = context;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public PermissionEnforcer getPermissionEnforcer() {
            return PermissionEnforcer.fromContext(mContext);
        }

        @Override
        public Looper getLooper() {
            ServiceThread serviceThread =
                    new ServiceThread(
                            TAG, android.os.Process.THREAD_PRIORITY_FOREGROUND, true /* allowIo */);
            serviceThread.start();
            return serviceThread.getLooper();
        }

        @Override
        public IntrusionDetectionEventTransportConnection
                getIntrusionDetectionEventransportConnection() {
            return new IntrusionDetectionEventTransportConnection(mContext);
        }

        @Override
        public DataAggregator getDataAggregator(
                IntrusionDetectionService intrusionDetectionService) {
            return new DataAggregator(mContext, intrusionDetectionService);
        }
    }
}

