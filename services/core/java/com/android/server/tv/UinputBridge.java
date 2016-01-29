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

import android.os.Binder;
import android.os.IBinder;

import java.io.IOException;

import dalvik.system.CloseGuard;

/**
 * Sends the input event to the linux driver.
 */
public final class UinputBridge {
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private long mPtr;
    private IBinder mToken = null;

    private static native long nativeOpen(String name, String uniqueId, int width, int height,
                                          int maxPointers);
    private static native void nativeClose(long ptr);
    private static native void nativeClear(long ptr);
    private static native void nativeSendTimestamp(long ptr, long timestamp);
    private static native void nativeSendKey(long ptr, int keyCode, boolean down);
    private static native void nativeSendPointerDown(long ptr, int pointerId, int x, int y);
    private static native void nativeSendPointerUp(long ptr, int pointerId);
    private static native void nativeSendPointerSync(long ptr);

    public UinputBridge(IBinder token, String name, int width, int height, int maxPointers)
                        throws IOException {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Touchpad must be at least 1x1.");
        }
        if (maxPointers < 1 || maxPointers > 32) {
            throw new IllegalArgumentException("Touchpad must support between 1 and 32 pointers.");
        }
        if (token == null) {
            throw new IllegalArgumentException("Token cannot be null");
        }
        mPtr = nativeOpen(name, token.toString(), width, height, maxPointers);
        if (mPtr == 0) {
            throw new IOException("Could not open uinput device " + name);
        }
        mToken = token;
        mCloseGuard.open("close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mPtr != 0) {
                mCloseGuard.warnIfOpen();
            }
            close(mToken);
        } finally {
            mToken = null;
            super.finalize();
        }
    }

    public void close(IBinder token) {
        if (isTokenValid(token)) {
            if (mPtr != 0) {
                clear(token);
                nativeClose(mPtr);

                mPtr = 0;
                mCloseGuard.close();
            }
        }
    }

    public IBinder getToken() {
        return mToken;
    }

    protected boolean isTokenValid(IBinder token) {
        return mToken.equals(token);
    }

    public void sendTimestamp(IBinder token, long timestamp) {
        if (isTokenValid(token)) {
            nativeSendTimestamp(mPtr, timestamp);
        }
    }

    public void sendKeyDown(IBinder token, int keyCode) {
        if (isTokenValid(token)) {
            nativeSendKey(mPtr, keyCode, true /*down*/);
        }
    }

    public void sendKeyUp(IBinder token, int keyCode) {
        if (isTokenValid(token)) {
            nativeSendKey(mPtr, keyCode, false /*down*/);
        }
    }

    public void sendPointerDown(IBinder token, int pointerId, int x, int y) {
        if (isTokenValid(token)) {
            nativeSendPointerDown(mPtr, pointerId, x, y);
        }
    }

    public void sendPointerUp(IBinder token, int pointerId) {
        if (isTokenValid(token)) {
            nativeSendPointerUp(mPtr, pointerId);
        }
    }

    public void sendPointerSync(IBinder token) {
        if (isTokenValid(token)) {
            nativeSendPointerSync(mPtr);
        }

    }

    public void clear(IBinder token) {
        if (isTokenValid(token)) {
            nativeClear(mPtr);
        }
    }

}
