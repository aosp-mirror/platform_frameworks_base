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
import android.os.RemoteException;
import android.util.Slog;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

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

    boolean enableWindowMagnification(int displayId, float scale, float centerX, float centerY) {
        try {
            mConnection.enableWindowMagnification(displayId, scale, centerX, centerY);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling enableWindowMagnification()");
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
                Slog.e(TAG, "Error calling setScale()");
            }
            return false;
        }
        return true;
    }

    boolean disableWindowMagnification(int displayId) {
        try {
            mConnection.disableWindowMagnification(displayId);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling disableWindowMagnification()");
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
                Slog.e(TAG, "Error calling moveWindowMagnifier()");
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
                Slog.e(TAG, "Error calling setConnectionCallback()");
            }
            return false;
        }
        return true;
    }

}
