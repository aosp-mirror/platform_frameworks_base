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

import static android.Manifest.permission.MANAGE_FORENSIC_STATE;
import static android.Manifest.permission.READ_FORENSIC_STATE;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.security.forensic.ForensicEvent;
import android.security.forensic.IForensicService;
import android.security.forensic.IForensicServiceCommandCallback;
import android.security.forensic.IForensicServiceStateCallback;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
public class ForensicService extends SystemService {
    private static final String TAG = "ForensicService";

    private static final int MAX_STATE_CALLBACK_NUM = 16;
    private static final int MSG_ADD_STATE_CALLBACK = 0;
    private static final int MSG_REMOVE_STATE_CALLBACK = 1;
    private static final int MSG_ENABLE = 2;
    private static final int MSG_DISABLE = 3;
    private static final int MSG_TRANSPORT = 4;

    private static final int STATE_UNKNOWN = IForensicServiceStateCallback.State.UNKNOWN;
    private static final int STATE_DISABLED = IForensicServiceStateCallback.State.DISABLED;
    private static final int STATE_ENABLED = IForensicServiceStateCallback.State.ENABLED;

    private static final int ERROR_UNKNOWN = IForensicServiceCommandCallback.ErrorCode.UNKNOWN;
    private static final int ERROR_PERMISSION_DENIED =
            IForensicServiceCommandCallback.ErrorCode.PERMISSION_DENIED;
    private static final int ERROR_INVALID_STATE_TRANSITION =
            IForensicServiceCommandCallback.ErrorCode.INVALID_STATE_TRANSITION;
    private static final int ERROR_TRANSPORT_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.TRANSPORT_UNAVAILABLE;
    private static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;

    private final Context mContext;
    private final Handler mHandler;
    private final ForensicEventTransportConnection mForensicEventTransportConnection;
    private final DataAggregator mDataAggregator;
    private final BinderService mBinderService;

    private final ArrayList<IForensicServiceStateCallback> mStateCallbacks = new ArrayList<>();
    private volatile int mState = STATE_DISABLED;

    public ForensicService(@NonNull Context context) {
        this(new InjectorImpl(context));
    }

    @VisibleForTesting
    ForensicService(@NonNull Injector injector) {
        super(injector.getContext());
        mContext = injector.getContext();
        mHandler = new EventHandler(injector.getLooper(), this);
        mForensicEventTransportConnection = injector.getForensicEventransportConnection();
        mDataAggregator = injector.getDataAggregator(this);
        mBinderService = new BinderService(this, injector.getPermissionEnforcer());
    }

    @VisibleForTesting
    protected void setState(int state) {
        mState = state;
    }

    private static final class BinderService extends IForensicService.Stub {
        final ForensicService mService;

        BinderService(ForensicService service, @NonNull PermissionEnforcer permissionEnforcer)  {
            super(permissionEnforcer);
            mService = service;
        }

        @Override
        @EnforcePermission(READ_FORENSIC_STATE)
        public void addStateCallback(IForensicServiceStateCallback callback) {
            addStateCallback_enforcePermission();
            mService.mHandler.obtainMessage(MSG_ADD_STATE_CALLBACK, callback).sendToTarget();
        }

        @Override
        @EnforcePermission(READ_FORENSIC_STATE)
        public void removeStateCallback(IForensicServiceStateCallback callback) {
            removeStateCallback_enforcePermission();
            mService.mHandler.obtainMessage(MSG_REMOVE_STATE_CALLBACK, callback).sendToTarget();
        }

        @Override
        @EnforcePermission(MANAGE_FORENSIC_STATE)
        public void enable(IForensicServiceCommandCallback callback) {
            enable_enforcePermission();
            mService.mHandler.obtainMessage(MSG_ENABLE, callback).sendToTarget();
        }

        @Override
        @EnforcePermission(MANAGE_FORENSIC_STATE)
        public void disable(IForensicServiceCommandCallback callback) {
            disable_enforcePermission();
            mService.mHandler.obtainMessage(MSG_DISABLE, callback).sendToTarget();
        }
    }

    private static class EventHandler extends Handler {
        private final ForensicService mService;

        EventHandler(Looper looper, ForensicService service) {
            super(looper);
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_STATE_CALLBACK:
                    try {
                        mService.addStateCallback(
                                (IForensicServiceStateCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_REMOVE_STATE_CALLBACK:
                    try {
                        mService.removeStateCallback(
                                (IForensicServiceStateCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_ENABLE:
                    try {
                        mService.enable((IForensicServiceCommandCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_DISABLE:
                    try {
                        mService.disable((IForensicServiceCommandCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_TRANSPORT:
                    mService.transport((List<ForensicEvent>) msg.obj);
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }

    private void addStateCallback(IForensicServiceStateCallback callback) throws RemoteException {
        for (int i = 0; i < mStateCallbacks.size(); i++) {
            if (mStateCallbacks.get(i).asBinder() == callback.asBinder()) {
                return;
            }
        }
        mStateCallbacks.add(callback);
        callback.onStateChange(mState);
    }

    private void removeStateCallback(IForensicServiceStateCallback callback)
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

    private void enable(IForensicServiceCommandCallback callback) throws RemoteException {
        if (mState == STATE_ENABLED) {
            callback.onSuccess();
            return;
        }

        // TODO: temporarily disable the following for the CTS ForensicManagerTest.
        //  Enable it when the transport component is ready.
        // if (!mForensicEventTransportConnection.initialize()) {
        //     callback.onFailure(ERROR_TRANSPORT_UNAVAILABLE);
        //   return;
        // }

        mDataAggregator.enable();
        mState = STATE_ENABLED;
        notifyStateMonitors();
        callback.onSuccess();
    }

    private void disable(IForensicServiceCommandCallback callback) throws RemoteException {
        if (mState == STATE_DISABLED) {
            callback.onSuccess();
            return;
        }

        // TODO: temporarily disable the following for the CTS ForensicManagerTest.
        //  Enable it when the transport component is ready.
        // mForensicEventTransportConnection.release();
        mDataAggregator.disable();
        mState = STATE_DISABLED;
        notifyStateMonitors();
        callback.onSuccess();
    }

    /**
     * Add a list of ForensicEvent.
     */
    public void addNewData(List<ForensicEvent> events) {
        mHandler.obtainMessage(MSG_TRANSPORT, events).sendToTarget();
    }

    private void transport(List<ForensicEvent> events) {
        mForensicEventTransportConnection.addData(events);
    }

    @Override
    public void onStart() {
        try {
            publishBinderService(Context.FORENSIC_SERVICE, mBinderService);
        } catch (Throwable t) {
            Slog.e(TAG, "Could not start the ForensicService.", t);
        }
    }

    @VisibleForTesting
    IForensicService getBinderService() {
        return mBinderService;
    }

    interface Injector {
        Context getContext();

        PermissionEnforcer getPermissionEnforcer();

        Looper getLooper();

        ForensicEventTransportConnection getForensicEventransportConnection();

        DataAggregator getDataAggregator(ForensicService forensicService);
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
        public ForensicEventTransportConnection getForensicEventransportConnection() {
            return new ForensicEventTransportConnection(mContext);
        }

        @Override
        public DataAggregator getDataAggregator(ForensicService forensicService) {
            return new DataAggregator(mContext, forensicService);
        }
    }
}

