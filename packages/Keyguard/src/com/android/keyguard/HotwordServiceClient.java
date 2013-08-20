/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.keyguard;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.google.android.search.service.IHotwordService;
import com.google.android.search.service.IHotwordServiceCallback;

/**
 * Utility class with its callbacks to simplify usage of {@link IHotwordService}.
 *
 * The client is meant to be used for a single hotword detection in a session.
 * start() -> stop(); client is asked to stop & disconnect from the service.
 * start() -> onHotwordDetected(); client disconnects from the service automatically.
 */
public class HotwordServiceClient implements Handler.Callback {
    private static final String TAG = "HotwordServiceClient";
    private static final boolean DBG = true;
    private static final String ACTION_HOTWORD =
            "com.google.android.search.service.IHotwordService";

    private static final int MSG_SERVICE_CONNECTED = 0;
    private static final int MSG_SERVICE_DISCONNECTED = 1;
    private static final int MSG_HOTWORD_STARTED = 2;
    private static final int MSG_HOTWORD_STOPPED = 3;
    private static final int MSG_HOTWORD_DETECTED = 4;

    private final Context mContext;
    private final Callback mClientCallback;
    private final Handler mHandler;

    private IHotwordService mService;

    public HotwordServiceClient(Context context, Callback callback) {
        mContext = context;
        mClientCallback = callback;
        mHandler = new Handler(this);
    }

    public interface Callback {
        void onServiceConnected();
        void onServiceDisconnected();
        void onHotwordDetectionStarted();
        void onHotwordDetectionStopped();
        void onHotwordDetected(String action);
    }

    /**
     * Binds to the {@link IHotwordService} and starts hotword detection
     * when the service is connected.
     *
     * @return false if the service can't be bound to.
     */
    public synchronized boolean start() {
        if (mService != null) {
            if (DBG) Log.d(TAG, "Multiple call to start(), service was already bound");
            return true;
        } else {
            // TODO: The hotword service is currently hosted within the search app
            // so the component handling the assist intent should handle hotwording
            // as well.
            final Intent intent =
                    ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                            .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
            if (intent == null) {
                return false;
            }

            Intent hotwordIntent = new Intent(ACTION_HOTWORD);
            hotwordIntent.fillIn(intent, Intent.FILL_IN_PACKAGE);
            return mContext.bindService(
                    hotwordIntent,
                   mConnection,
                   Context.BIND_AUTO_CREATE);
        }
    }

    /**
     * Unbinds from the the {@link IHotwordService}.
     */
    public synchronized void stop() {
        if (mService != null) {
            mContext.unbindService(mConnection);
            mService = null;
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SERVICE_CONNECTED:
                handleServiceConnected();
                break;
            case MSG_SERVICE_DISCONNECTED:
                handleServiceDisconnected();
                break;
            case MSG_HOTWORD_STARTED:
                handleHotwordDetectionStarted();
                break;
            case MSG_HOTWORD_STOPPED:
                handleHotwordDetectionStopped();
                break;
            case MSG_HOTWORD_DETECTED:
                handleHotwordDetected((String) msg.obj);
                break;
            default:
                if (DBG) Log.e(TAG, "Unhandled message");
                return false;
        }
        return true;
    }

    private void handleServiceConnected() {
        if (DBG) Log.d(TAG, "handleServiceConnected()");
        if (mClientCallback != null) mClientCallback.onServiceConnected();
        try {
            mService.requestHotwordDetection(mServiceCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while registering callback", e);
            mHandler.sendEmptyMessage(MSG_SERVICE_DISCONNECTED);
        }
    }

    private void handleServiceDisconnected() {
        if (DBG) Log.d(TAG, "handleServiceDisconnected()");
        mService = null;
        if (mClientCallback != null) mClientCallback.onServiceDisconnected();
    }

    private void handleHotwordDetectionStarted() {
        if (DBG) Log.d(TAG, "handleHotwordDetectionStarted()");
        if (mClientCallback != null) mClientCallback.onHotwordDetectionStarted();
    }

    private void handleHotwordDetectionStopped() {
        if (DBG) Log.d(TAG, "handleHotwordDetectionStopped()");
        if (mClientCallback != null) mClientCallback.onHotwordDetectionStopped();
    }

    void handleHotwordDetected(final String action) {
        if (DBG) Log.d(TAG, "handleHotwordDetected()");
        if (mClientCallback != null) mClientCallback.onHotwordDetected(action);
        stop();
    }

    /**
     * Implements service connection methods.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        /**
         * Called when the service connects after calling bind().
         */
        public void onServiceConnected(ComponentName className, IBinder iservice) {
            mService = IHotwordService.Stub.asInterface(iservice);
            mHandler.sendEmptyMessage(MSG_SERVICE_CONNECTED);
        }

        /**
         * Called if the service unexpectedly disconnects. This indicates an error.
         */
        public void onServiceDisconnected(ComponentName className) {
            mService = null;
            mHandler.sendEmptyMessage(MSG_SERVICE_DISCONNECTED);
        }
    };

    /**
     * Implements the AIDL IHotwordServiceCallback interface.
     */
    private final IHotwordServiceCallback mServiceCallback = new IHotwordServiceCallback.Stub() {

        public void onHotwordDetectionStarted() {
            mHandler.sendEmptyMessage(MSG_HOTWORD_STARTED);
        }

        public void onHotwordDetectionStopped() {
            mHandler.sendEmptyMessage(MSG_HOTWORD_STOPPED);
        }

        public void onHotwordDetected(String action) {
            mHandler.obtainMessage(MSG_HOTWORD_DETECTED, action).sendToTarget();
        }
    };
}
