/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.tv;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.SystemService;
import com.android.server.Watchdog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * TvRemoteService represents a system service that allows a connected
 * remote control (emote) service to inject white-listed input events
 * and call other specified methods for functioning as an emote service.
 * <p/>
 * This service is intended for use only by white-listed packages.
 */
public class TvRemoteService extends SystemService implements Watchdog.Monitor {
    private static final String TAG = "TvRemoteService";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_KEYS = false;

    private Map<IBinder, UinputBridge> mBridgeMap = new ArrayMap();
    private Map<IBinder, TvRemoteProviderProxy> mProviderMap = new ArrayMap();
    private ArrayList<TvRemoteProviderProxy> mProviderList = new ArrayList<>();

    /**
     * State guarded by mLock.
     *  This is the second lock in sequence for an incoming call.
     *  The first lock is always {@link TvRemoteProviderProxy#mLock}
     *
     *  There are currently no methods that break this sequence.
     *  Special note:
     *  Outgoing call informInputBridgeConnected(), which is called from
     *  openInputBridgeInternalLocked() uses a handler thereby relinquishing held locks.
     */
    private final Object mLock = new Object();

    public final UserHandler mHandler;

    public TvRemoteService(Context context) {
        super(context);
        mHandler = new UserHandler(new UserProvider(TvRemoteService.this), context);
        Watchdog.getInstance().addMonitor(this);
    }

    @Override
    public void onStart() {
        if (DEBUG) Slog.d(TAG, "onStart()");
    }

    @Override
    public void monitor() {
        synchronized (mLock) { /* check for deadlock */ }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            if (DEBUG) Slog.d(TAG, "PHASE_THIRD_PARTY_APPS_CAN_START");
            mHandler.sendEmptyMessage(UserHandler.MSG_START);
        }
    }

    //Outgoing calls.
    private void informInputBridgeConnected(IBinder token) {
        mHandler.obtainMessage(UserHandler.MSG_INPUT_BRIDGE_CONNECTED, 0, 0, token).sendToTarget();
    }

    // Incoming calls.
    private void openInputBridgeInternalLocked(TvRemoteProviderProxy provider, final IBinder token,
                                               String name, int width, int height,
                                               int maxPointers) {
        if (DEBUG) {
            Slog.d(TAG, "openInputBridgeInternalLocked(), token: " + token + ", name: " + name +
                    ", width: " + width + ", height: " + height + ", maxPointers: " + maxPointers);
        }

        try {
            //Create a new bridge, if one does not exist already
            if (mBridgeMap.containsKey(token)) {
                if (DEBUG) Slog.d(TAG, "RemoteBridge already exists");
                // Respond back with success.
                informInputBridgeConnected(token);
                return;
            }

            UinputBridge inputBridge = new UinputBridge(token, name, width, height, maxPointers);

            mBridgeMap.put(token, inputBridge);
            mProviderMap.put(token, provider);

            try {
                token.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        synchronized (mLock) {
                            closeInputBridgeInternalLocked(token);
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                if (DEBUG) Slog.d(TAG, "Token is already dead");
                closeInputBridgeInternalLocked(token);
                return;
            }

            // Respond back with success.
            informInputBridgeConnected(token);

        } catch (IOException ioe) {
            Slog.e(TAG, "Cannot create device for " + name);
        }
    }

    private void closeInputBridgeInternalLocked(IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "closeInputBridgeInternalLocked(), token: " + token);
        }

        // Close an existing RemoteBridge
        UinputBridge inputBridge = mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.close(token);
        }

        mBridgeMap.remove(token);
        mProviderMap.remove(token);
    }

    private void clearInputBridgeInternalLocked(IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "clearInputBridgeInternalLocked(), token: " + token);
        }

        UinputBridge inputBridge = mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.clear(token);
        }
    }

    private void sendKeyDownInternalLocked(IBinder token, int keyCode) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendKeyDownInternalLocked(), token: " + token + ", keyCode: " + keyCode);
        }

        UinputBridge inputBridge = mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendKeyDown(token, keyCode);
        }
    }

    private void sendKeyUpInternalLocked(IBinder token, int keyCode) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendKeyUpInternalLocked(), token: " + token + ", keyCode: " + keyCode);
        }

        UinputBridge inputBridge = mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendKeyUp(token, keyCode);
        }
    }

    private void sendPointerDownInternalLocked(IBinder token, int pointerId, int x, int y) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendPointerDownInternalLocked(), token: " + token + ", pointerId: " +
                    pointerId + ", x: " + x + ", y: " + y);
        }

        UinputBridge inputBridge = mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendPointerDown(token, pointerId, x, y);
        }
    }

    private void sendPointerUpInternalLocked(IBinder token, int pointerId) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendPointerUpInternalLocked(), token: " + token + ", pointerId: " +
                    pointerId);
        }

        UinputBridge inputBridge = mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendPointerUp(token, pointerId);
        }
    }

    private void sendPointerSyncInternalLocked(IBinder token) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendPointerSyncInternalLocked(), token: " + token);
        }

        UinputBridge inputBridge = mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendPointerSync(token);
        }
    }

    private final class UserHandler extends Handler {

        public static final int MSG_START = 1;
        public static final int MSG_INPUT_BRIDGE_CONNECTED = 2;

        private final TvRemoteProviderWatcher mWatcher;
        private boolean mRunning;

        public UserHandler(UserProvider provider, Context context) {
            super(Looper.getMainLooper(), null, true);
            mWatcher = new TvRemoteProviderWatcher(context, provider, this);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START: {
                    start();
                    break;
                }
                case MSG_INPUT_BRIDGE_CONNECTED: {
                    IBinder token = (IBinder) msg.obj;
                    TvRemoteProviderProxy provider = mProviderMap.get(token);
                    if (provider != null) {
                        provider.inputBridgeConnected(token);
                    }
                    break;
                }
            }
        }

        private void start() {
            if (!mRunning) {
                mRunning = true;
                mWatcher.start(); // also starts all providers
            }
        }
    }

    private final class UserProvider implements TvRemoteProviderWatcher.ProviderMethods,
            TvRemoteProviderProxy.ProviderMethods {

        private final TvRemoteService mService;

        public UserProvider(TvRemoteService service) {
            mService = service;
        }

        @Override
        public void openInputBridge(TvRemoteProviderProxy provider, IBinder token, String name,
                                    int width, int height, int maxPointers) {
            if (DEBUG) {
                Slog.d(TAG, "openInputBridge(), token: " + token +
                        ", name: " + name + ", width: " + width +
                        ", height: " + height + ", maxPointers: " + maxPointers);
            }

            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.openInputBridgeInternalLocked(provider, token, name, width, height,
                            maxPointers);
                }
            }
        }

        @Override
        public void closeInputBridge(TvRemoteProviderProxy provider, IBinder token) {
            if (DEBUG) Slog.d(TAG, "closeInputBridge(), token: " + token);
            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.closeInputBridgeInternalLocked(token);
                }
            }
        }

        @Override
        public void clearInputBridge(TvRemoteProviderProxy provider, IBinder token) {
            if (DEBUG) Slog.d(TAG, "clearInputBridge(), token: " + token);
            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.clearInputBridgeInternalLocked(token);
                }
            }
        }

        @Override
        public void sendKeyDown(TvRemoteProviderProxy provider, IBinder token, int keyCode) {
            if (DEBUG_KEYS) {
                Slog.d(TAG, "sendKeyDown(), token: " + token + ", keyCode: " + keyCode);
            }
            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.sendKeyDownInternalLocked(token, keyCode);
                }
            }
        }

        @Override
        public void sendKeyUp(TvRemoteProviderProxy provider, IBinder token, int keyCode) {
            if (DEBUG_KEYS) {
                Slog.d(TAG, "sendKeyUp(), token: " + token + ", keyCode: " + keyCode);
            }
            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.sendKeyUpInternalLocked(token, keyCode);
                }
            }
        }

        @Override
        public void sendPointerDown(TvRemoteProviderProxy provider, IBinder token, int pointerId,
                                    int x, int y) {
            if (DEBUG_KEYS) {
                Slog.d(TAG, "sendPointerDown(), token: " + token + ", pointerId: " + pointerId);
            }
            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.sendPointerDownInternalLocked(token, pointerId, x, y);
                }
            }
        }

        @Override
        public void sendPointerUp(TvRemoteProviderProxy provider, IBinder token, int pointerId) {
            if (DEBUG_KEYS) {
                Slog.d(TAG, "sendPointerUp(), token: " + token + ", pointerId: " + pointerId);
            }
            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.sendPointerUpInternalLocked(token, pointerId);
                }
            }
        }

        @Override
        public void sendPointerSync(TvRemoteProviderProxy provider, IBinder token) {
            if (DEBUG_KEYS) Slog.d(TAG, "sendPointerSync(), token: " + token);
            synchronized (mLock) {
                if (mProviderList.contains(provider)) {
                    mService.sendPointerSyncInternalLocked(token);
                }
            }
        }

        @Override
        public void addProvider(TvRemoteProviderProxy provider) {
            if (DEBUG) Slog.d(TAG, "addProvider " + provider);
            synchronized (mLock) {
                provider.setProviderSink(this);
                mProviderList.add(provider);
                Slog.d(TAG, "provider: " + provider.toString());
            }
        }

        @Override
        public void removeProvider(TvRemoteProviderProxy provider) {
            if (DEBUG) Slog.d(TAG, "removeProvider " + provider);
            synchronized (mLock) {
                if (mProviderList.remove(provider) == false) {
                    Slog.e(TAG, "Unknown provider " + provider);
                }
            }
        }
    }
}
