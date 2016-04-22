/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.media.tv.remoteprovider;

import android.content.Context;
import android.media.tv.ITvRemoteProvider;
import android.media.tv.ITvRemoteServiceInput;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

/**
 * Base class for emote providers implemented in unbundled service.
 * <p/>
 * This object is not thread safe.  It is only intended to be accessed on the
 * {@link Context#getMainLooper main looper thread} of an application.
 * </p><p>
 * IMPORTANT: This class is effectively a system API for unbundled emote service, and
 * must remain API stable. See README.txt in the root of this package for more information.
 * </p>
 */


public abstract class TvRemoteProvider {

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * The service must also require the {@link android.Manifest.permission#BIND_TV_REMOTE_SERVICE}
     * permission so that other applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "com.android.media.tv.remoteprovider.TvRemoteProvider";

    private static final String TAG = "TvRemoteProvider";
    private static final boolean DEBUG_KEYS = false;
    private static final int MSG_SET_SERVICE_INPUT = 1;
    private static final int MSG_SEND_INPUTBRIDGE_CONNECTED = 2;
    private final Context mContext;
    private final ProviderStub mStub;
    private final ProviderHandler mHandler;
    private ITvRemoteServiceInput mRemoteServiceInput;

    /**
     * Creates a provider for an unbundled emote controller
     * service allowing it to interface with the tv remote controller
     * system service.
     *
     * @param context The application context for the remote provider.
     */
    public TvRemoteProvider(Context context) {
        mContext = context.getApplicationContext();
        mStub = new ProviderStub();
        mHandler = new ProviderHandler(mContext.getMainLooper());
    }

    /**
     * Gets the context of the remote service provider.
     */
    public final Context getContext() {
        return mContext;
    }


    /**
     * Gets the Binder associated with the provider.
     * <p>
     * This is intended to be used for the onBind() method of a service that implements
     * a remote provider service.
     * </p>
     *
     * @return The IBinder instance associated with the provider.
     */
    public IBinder getBinder() {
        return mStub;
    }

    /**
     * Information about the InputBridge connected status.
     *
     * @param token Identifier for the connection. Null, if failed.
     */
    public void onInputBridgeConnected(IBinder token) {
    }

    /**
     * Set a sink for sending events to framework service.
     *
     * @param tvServiceInput sink defined in framework service
     */
    private void setRemoteServiceInputSink(ITvRemoteServiceInput tvServiceInput) {
        mRemoteServiceInput = tvServiceInput;
    }

    /**
     * openRemoteInputBridge : Open an input bridge for a particular device.
     * Clients should pass in a token that can be used to match this request with a token that
     * will be returned by {@link TvRemoteProvider#onInputBridgeConnected(IBinder token)}
     * <p>
     * The token should be used for subsequent calls.
     * </p>
     *
     * @param name        Device name
     * @param token       Identifier for this connection
     * @param width       Width of the device's virtual touchpad
     * @param height      Height of the device's virtual touchpad
     * @param maxPointers Maximum supported pointers
     * @throws RuntimeException
     */
    public void openRemoteInputBridge(IBinder token, String name, int width, int height,
                                      int maxPointers) throws RuntimeException {
        try {
            mRemoteServiceInput.openInputBridge(token, name, width, height, maxPointers);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * closeInputBridge : Close input bridge for a device
     *
     * @param token identifier for this connection
     * @throws RuntimeException
     */
    public void closeInputBridge(IBinder token) throws RuntimeException {
        try {
            mRemoteServiceInput.closeInputBridge(token);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * clearInputBridge : Clear out any existing key or pointer events in queue for this device by
     *                    dropping them on the floor and sending an UP to all keys and pointer
     *                    slots.
     *
     * @param token identifier for this connection
     * @throws RuntimeException
     */
    public void clearInputBridge(IBinder token) throws RuntimeException {
        if (DEBUG_KEYS) Log.d(TAG, "clearInputBridge() token " + token);
        try {
            mRemoteServiceInput.clearInputBridge(token);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendTimestamp : Send a timestamp for a set of pointer events
     *
     * @param token     identifier for the device
     * @param timestamp Timestamp to be used in
     *                  {@link android.os.SystemClock#uptimeMillis} time base
     * @throws RuntimeException
     */
    public void sendTimestamp(IBinder token, long timestamp) throws RuntimeException {
        if (DEBUG_KEYS) Log.d(TAG, "sendTimestamp() token: " + token +
                ", timestamp: " + timestamp);
        try {
            mRemoteServiceInput.sendTimestamp(token, timestamp);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendKeyUp : Send key up event for a device
     *
     * @param token   identifier for this connection
     * @param keyCode Key code to be sent
     * @throws RuntimeException
     */
    public void sendKeyUp(IBinder token, int keyCode) throws RuntimeException {
        if (DEBUG_KEYS) Log.d(TAG, "sendKeyUp() token: " + token + ", keyCode: " + keyCode);
        try {
            mRemoteServiceInput.sendKeyUp(token, keyCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendKeyDown : Send key down event for a device
     *
     * @param token   identifier for this connection
     * @param keyCode Key code to be sent
     * @throws RuntimeException
     */
    public void sendKeyDown(IBinder token, int keyCode) throws RuntimeException {
        if (DEBUG_KEYS) Log.d(TAG, "sendKeyDown() token: " + token +
                ", keyCode: " + keyCode);
        try {
            mRemoteServiceInput.sendKeyDown(token, keyCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendPointerUp : Send pointer up event for a device
     *
     * @param token     identifier for the device
     * @param pointerId Pointer id to be used. Value may be from 0
     *                  to {@link MotionEvent#getPointerCount()} -1
     * @throws RuntimeException
     */
    public void sendPointerUp(IBinder token, int pointerId) throws RuntimeException {
        if (DEBUG_KEYS) Log.d(TAG, "sendPointerUp() token: " + token +
                ", pointerId: " + pointerId);
        try {
            mRemoteServiceInput.sendPointerUp(token, pointerId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendPointerDown : Send pointer down event for a device
     *
     * @param token     identifier for the device
     * @param pointerId Pointer id to be used. Value may be from 0
     *                  to {@link MotionEvent#getPointerCount()} -1
     * @param x         X co-ordinates in display pixels
     * @param y         Y co-ordinates in display pixels
     * @throws RuntimeException
     */
    public void sendPointerDown(IBinder token, int pointerId, int x, int y)
            throws RuntimeException {
        if (DEBUG_KEYS) Log.d(TAG, "sendPointerDown() token: " + token +
                ", pointerId: " + pointerId);
        try {
            mRemoteServiceInput.sendPointerDown(token, pointerId, x, y);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendPointerSync : Send pointer sync event for a device
     *
     * @param token identifier for the device
     * @throws RuntimeException
     */
    public void sendPointerSync(IBinder token) throws RuntimeException {
        if (DEBUG_KEYS) Log.d(TAG, "sendPointerSync() token: " + token);
        try {
            mRemoteServiceInput.sendPointerSync(token);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private final class ProviderStub extends ITvRemoteProvider.Stub {
        @Override
        public void setRemoteServiceInputSink(ITvRemoteServiceInput tvServiceInput) {
            mHandler.obtainMessage(MSG_SET_SERVICE_INPUT, tvServiceInput).sendToTarget();
        }

        @Override
        public void onInputBridgeConnected(IBinder token) {
            mHandler.obtainMessage(MSG_SEND_INPUTBRIDGE_CONNECTED, 0, 0,
                    (IBinder) token).sendToTarget();
        }
    }

    private final class ProviderHandler extends Handler {
        public ProviderHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_SERVICE_INPUT: {
                    setRemoteServiceInputSink((ITvRemoteServiceInput) msg.obj);
                    break;
                }
                case MSG_SEND_INPUTBRIDGE_CONNECTED: {
                    onInputBridgeConnected((IBinder) msg.obj);
                    break;
                }
            }
        }
    }
}
