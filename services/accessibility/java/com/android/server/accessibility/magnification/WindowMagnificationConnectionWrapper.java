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

package com.android.server.accessibility.magnification;

import static android.os.IBinder.DeathRecipient;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.Slog;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;
import android.view.accessibility.MagnificationAnimationCallback;

/**
 * A wrapper of {@link IWindowMagnificationConnection}.
 */
class WindowMagnificationConnectionWrapper {

    private static final boolean DBG = false;
    private static final String TAG = "WindowMagnificationConnectionWrapper";

    private final @NonNull IWindowMagnificationConnection mConnection;

    WindowMagnificationConnectionWrapper(@NonNull IWindowMagnificationConnection connection) {
        mConnection = connection;
    }

    //Should not use this instance anymore after calling it.
    void unlinkToDeath(@NonNull DeathRecipient deathRecipient) {
        mConnection.asBinder().unlinkToDeath(deathRecipient, 0);
    }

    void linkToDeath(@NonNull DeathRecipient deathRecipient) throws RemoteException {
        mConnection.asBinder().linkToDeath(deathRecipient, 0);
    }

    boolean enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            @Nullable MagnificationAnimationCallback callback) {
        try {
            mConnection.enableWindowMagnification(displayId, scale, centerX, centerY,
                    transformToRemoteCallback(callback));
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling enableWindowMagnification()", e);
            }
            return false;
        }
        return true;
    }

    boolean setScale(int displayId, float scale) {
        try {
            mConnection.setScale(displayId, scale);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling setScale()", e);
            }
            return false;
        }
        return true;
    }

    boolean disableWindowMagnification(int displayId,
            @Nullable MagnificationAnimationCallback callback) {
        try {
            mConnection.disableWindowMagnification(displayId, transformToRemoteCallback(callback));
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling disableWindowMagnification()", e);
            }
            return false;
        }
        return true;
    }

    boolean moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        try {
            mConnection.moveWindowMagnifier(displayId, offsetX, offsetY);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling moveWindowMagnifier()", e);
            }
            return false;
        }
        return true;
    }

    boolean showMagnificationButton(int displayId, int magnificationMode) {
        try {
            mConnection.showMagnificationButton(displayId, magnificationMode);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling showMagnificationButton()", e);
            }
            return false;
        }
        return true;
    }

    boolean removeMagnificationButton(int displayId) {
        try {
            mConnection.removeMagnificationButton(displayId);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling removeMagnificationButton()", e);
            }
            return false;
        }
        return true;
    }

    boolean setConnectionCallback(IWindowMagnificationConnectionCallback connectionCallback) {
        try {
            mConnection.setConnectionCallback(connectionCallback);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling setConnectionCallback()", e);
            }
            return false;
        }
        return true;
    }

    private static @Nullable
            IRemoteMagnificationAnimationCallback transformToRemoteCallback(
            MagnificationAnimationCallback callback) {
        if (callback == null) {
            return null;
        }
        return new RemoteAnimationCallback(callback);
    }

    private static class RemoteAnimationCallback extends
            IRemoteMagnificationAnimationCallback.Stub {

        private final MagnificationAnimationCallback mCallback;

        RemoteAnimationCallback(@NonNull MagnificationAnimationCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onResult(boolean success) throws RemoteException {
            mCallback.onResult(success);
        }
    }
}
