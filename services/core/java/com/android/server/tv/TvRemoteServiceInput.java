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

package com.android.server.tv;

import android.media.tv.ITvRemoteProvider;
import android.media.tv.ITvRemoteServiceInput;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.IOException;
import java.util.Map;

final class TvRemoteServiceInput extends ITvRemoteServiceInput.Stub {
    private static final String TAG = "TvRemoteServiceInput";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_KEYS = false;

    private final Map<IBinder, UinputBridge> mBridgeMap;
    private final Object mLock;
    private final ITvRemoteProvider mProvider;

    TvRemoteServiceInput(Object lock, ITvRemoteProvider provider) {
        mBridgeMap = new ArrayMap();
        mLock = lock;
        mProvider = provider;
    }

    @Override
    public void openInputBridge(IBinder token, String name, int width,
                                int height, int maxPointers) {
        if (DEBUG) {
            Slog.d(TAG, "openInputBridge(), token: " + token
                    + ", name: " + name + ", width: " + width
                    + ", height: " + height + ", maxPointers: " + maxPointers);
        }

        synchronized (mLock) {
            if (mBridgeMap.containsKey(token)) {
                if (DEBUG) {
                    Slog.d(TAG, "InputBridge already exists");
                }
            } else {
                final long idToken = Binder.clearCallingIdentity();
                try {
                    mBridgeMap.put(token,
                                   new UinputBridge(token, name, width, height, maxPointers));
                    token.linkToDeath(new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            closeInputBridge(token);
                        }
                    }, 0);
                } catch (IOException e) {
                    Slog.e(TAG, "Cannot create device for " + name);
                    return;
                } catch (RemoteException e) {
                    Slog.e(TAG, "Token is already dead");
                    closeInputBridge(token);
                    return;
                } finally {
                    Binder.restoreCallingIdentity(idToken);
                }
            }
        }

        try {
            mProvider.onInputBridgeConnected(token);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed remote call to onInputBridgeConnected");
        }
    }

    @Override
    public void openGamepadBridge(IBinder token, String name) throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, String.format("openGamepadBridge(), token: %s, name: %s", token, name));
        }

        synchronized (mLock) {
            if (mBridgeMap.containsKey(token)) {
                if (DEBUG) {
                    Slog.d(TAG, "InputBridge already exists");
                }
            } else {
                final long idToken = Binder.clearCallingIdentity();
                try {
                    mBridgeMap.put(token, UinputBridge.openGamepad(token, name));
                    token.linkToDeath(new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            closeInputBridge(token);
                        }
                    }, 0);
                } catch (IOException e) {
                    Slog.e(TAG, "Cannot create device for " + name);
                    return;
                } catch (RemoteException e) {
                    Slog.e(TAG, "Token is already dead");
                    closeInputBridge(token);
                    return;
                } finally {
                    Binder.restoreCallingIdentity(idToken);
                }
            }
        }

        try {
            mProvider.onInputBridgeConnected(token);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed remote call to onInputBridgeConnected");
        }
    }

    @Override
    public void closeInputBridge(IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "closeInputBridge(), token: " + token);
        }

        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.remove(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.close(token);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void clearInputBridge(IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "clearInputBridge, token: " + token);
        }

        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.clear(token);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendTimestamp(IBinder token, long timestamp) {
        if (DEBUG) {
            Slog.e(TAG, "sendTimestamp is deprecated, please remove all usages of this API.");
        }
    }

    @Override
    public void sendKeyDown(IBinder token, int keyCode) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendKeyDown(), token: " + token + ", keyCode: " + keyCode);
        }

        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendKeyDown(token, keyCode);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendKeyUp(IBinder token, int keyCode) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendKeyUp(), token: " + token + ", keyCode: " + keyCode);
        }

        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendKeyUp(token, keyCode);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendPointerDown(IBinder token, int pointerId, int x, int y) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendPointerDown(), token: " + token + ", pointerId: "
                    + pointerId + ", x: " + x + ", y: " + y);
        }

        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendPointerDown(token, pointerId, x, y);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendPointerUp(IBinder token, int pointerId) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendPointerUp(), token: " + token + ", pointerId: " + pointerId);
        }

        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendPointerUp(token, pointerId);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendPointerSync(IBinder token) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, "sendPointerSync(), token: " + token);
        }

        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendPointerSync(token);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendGamepadKeyUp(IBinder token, int keyIndex) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, String.format("sendGamepadKeyUp(), token: %s", token));
        }
        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendGamepadKey(token, keyIndex, false);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendGamepadKeyDown(IBinder token, int keyCode) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, String.format("sendGamepadKeyDown(), token: %s", token));
        }
        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendGamepadKey(token, keyCode, true);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }

    @Override
    public void sendGamepadAxisValue(IBinder token, int axis, float value) {
        if (DEBUG_KEYS) {
            Slog.d(TAG, String.format("sendGamepadAxisValue(), token: %s", token));
        }
        synchronized (mLock) {
            UinputBridge inputBridge = mBridgeMap.get(token);
            if (inputBridge == null) {
                Slog.w(TAG, String.format("Input bridge not found for token: %s", token));
                return;
            }

            final long idToken = Binder.clearCallingIdentity();
            try {
                inputBridge.sendGamepadAxisValue(token, axis, value);
            } finally {
                Binder.restoreCallingIdentity(idToken);
            }
        }
    }
}
