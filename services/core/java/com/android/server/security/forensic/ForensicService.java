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

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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

    private static final int MSG_MONITOR_STATE = 0;
    private static final int MSG_MAKE_VISIBLE = 1;
    private static final int MSG_MAKE_INVISIBLE = 2;
    private static final int MSG_ENABLE = 3;
    private static final int MSG_DISABLE = 4;
    private static final int MSG_BACKUP = 5;

    private static final int STATE_UNKNOWN = IForensicServiceStateCallback.State.UNKNOWN;
    private static final int STATE_INVISIBLE = IForensicServiceStateCallback.State.INVISIBLE;
    private static final int STATE_VISIBLE = IForensicServiceStateCallback.State.VISIBLE;
    private static final int STATE_ENABLED = IForensicServiceStateCallback.State.ENABLED;

    private static final int ERROR_UNKNOWN = IForensicServiceCommandCallback.ErrorCode.UNKNOWN;
    private static final int ERROR_PERMISSION_DENIED =
            IForensicServiceCommandCallback.ErrorCode.PERMISSION_DENIED;
    private static final int ERROR_INVALID_STATE_TRANSITION =
            IForensicServiceCommandCallback.ErrorCode.INVALID_STATE_TRANSITION;
    private static final int ERROR_BACKUP_TRANSPORT_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.BACKUP_TRANSPORT_UNAVAILABLE;
    private static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;

    private final Context mContext;
    private final Handler mHandler;
    private final BackupTransportConnection mBackupTransportConnection;
    private final DataAggregator mDataAggregator;
    private final BinderService mBinderService;

    private final ArrayList<IForensicServiceStateCallback> mStateMonitors = new ArrayList<>();
    private volatile int mState = STATE_INVISIBLE;

    public ForensicService(@NonNull Context context) {
        this(new InjectorImpl(context));
    }

    @VisibleForTesting
    ForensicService(@NonNull Injector injector) {
        super(injector.getContext());
        mContext = injector.getContext();
        mHandler = new EventHandler(injector.getLooper(), this);
        mBackupTransportConnection = injector.getBackupTransportConnection();
        mDataAggregator = injector.getDataAggregator(this);
        mBinderService = new BinderService(this);
    }

    @VisibleForTesting
    protected void setState(int state) {
        mState = state;
    }

    private static final class BinderService extends IForensicService.Stub {
        final ForensicService mService;

        BinderService(ForensicService service)  {
            mService = service;
        }

        @Override
        public void monitorState(IForensicServiceStateCallback callback) {
            mService.mHandler.obtainMessage(MSG_MONITOR_STATE, callback).sendToTarget();
        }

        @Override
        public void makeVisible(IForensicServiceCommandCallback callback) {
            mService.mHandler.obtainMessage(MSG_MAKE_VISIBLE, callback).sendToTarget();
        }

        @Override
        public void makeInvisible(IForensicServiceCommandCallback callback) {
            mService.mHandler.obtainMessage(MSG_MAKE_INVISIBLE, callback).sendToTarget();
        }

        @Override
        public void enable(IForensicServiceCommandCallback callback) {
            mService.mHandler.obtainMessage(MSG_ENABLE, callback).sendToTarget();
        }

        @Override
        public void disable(IForensicServiceCommandCallback callback) {
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
                case MSG_MONITOR_STATE:
                    try {
                        mService.monitorState(
                                (IForensicServiceStateCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_MAKE_VISIBLE:
                    try {
                        mService.makeVisible((IForensicServiceCommandCallback) msg.obj);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException", e);
                    }
                    break;
                case MSG_MAKE_INVISIBLE:
                    try {
                        mService.makeInvisible((IForensicServiceCommandCallback) msg.obj);
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
                case MSG_BACKUP:
                    mService.backup((List<ForensicEvent>) msg.obj);
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }

    private void monitorState(IForensicServiceStateCallback callback) throws RemoteException {
        for (int i = 0; i < mStateMonitors.size(); i++) {
            if (mStateMonitors.get(i).asBinder() == callback.asBinder()) {
                return;
            }
        }
        mStateMonitors.add(callback);
        callback.onStateChange(mState);
    }

    private void notifyStateMonitors() throws RemoteException {
        for (int i = 0; i < mStateMonitors.size(); i++) {
            mStateMonitors.get(i).onStateChange(mState);
        }
    }

    private void makeVisible(IForensicServiceCommandCallback callback) throws RemoteException {
        switch (mState) {
            case STATE_INVISIBLE:
                if (!mDataAggregator.initialize()) {
                    callback.onFailure(ERROR_DATA_SOURCE_UNAVAILABLE);
                    break;
                }
                mState = STATE_VISIBLE;
                notifyStateMonitors();
                callback.onSuccess();
                break;
            case STATE_VISIBLE:
                callback.onSuccess();
                break;
            default:
                callback.onFailure(ERROR_INVALID_STATE_TRANSITION);
        }
    }

    private void makeInvisible(IForensicServiceCommandCallback callback) throws RemoteException {
        switch (mState) {
            case STATE_VISIBLE:
            case STATE_ENABLED:
                mState = STATE_INVISIBLE;
                notifyStateMonitors();
                callback.onSuccess();
                break;
            case STATE_INVISIBLE:
                callback.onSuccess();
                break;
            default:
                callback.onFailure(ERROR_INVALID_STATE_TRANSITION);
        }
    }

    private void enable(IForensicServiceCommandCallback callback) throws RemoteException {
        switch (mState) {
            case STATE_VISIBLE:
                if (!mBackupTransportConnection.initialize()) {
                    callback.onFailure(ERROR_BACKUP_TRANSPORT_UNAVAILABLE);
                    break;
                }
                mDataAggregator.enable();
                mState = STATE_ENABLED;
                notifyStateMonitors();
                callback.onSuccess();
                break;
            case STATE_ENABLED:
                callback.onSuccess();
                break;
            default:
                callback.onFailure(ERROR_INVALID_STATE_TRANSITION);
        }
    }

    private void disable(IForensicServiceCommandCallback callback) throws RemoteException {
        switch (mState) {
            case STATE_ENABLED:
                mBackupTransportConnection.release();
                mDataAggregator.disable();
                mState = STATE_VISIBLE;
                notifyStateMonitors();
                callback.onSuccess();
                break;
            case STATE_VISIBLE:
                callback.onSuccess();
                break;
            default:
                callback.onFailure(ERROR_INVALID_STATE_TRANSITION);
        }
    }

    /**
     * Add a list of ForensicEvent.
     */
    public void addNewData(List<ForensicEvent> events) {
        mHandler.obtainMessage(MSG_BACKUP, events).sendToTarget();
    }

    private void backup(List<ForensicEvent> events) {
        mBackupTransportConnection.addData(events);
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

        Looper getLooper();

        BackupTransportConnection getBackupTransportConnection();

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
        public Looper getLooper() {
            ServiceThread serviceThread =
                    new ServiceThread(
                            TAG, android.os.Process.THREAD_PRIORITY_FOREGROUND, true /* allowIo */);
            serviceThread.start();
            return serviceThread.getLooper();
        }

        @Override
        public BackupTransportConnection getBackupTransportConnection() {
            return new BackupTransportConnection(mContext);
        }

        @Override
        public DataAggregator getDataAggregator(ForensicService forensicService) {
            return new DataAggregator(mContext, forensicService);
        }
    }
}

