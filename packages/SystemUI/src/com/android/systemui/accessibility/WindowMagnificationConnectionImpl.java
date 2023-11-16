/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import com.android.systemui.dagger.qualifiers.Main;

/**
 * Implementation of window magnification connection.
 *
 * @see IWindowMagnificationConnection
 */
class WindowMagnificationConnectionImpl extends IWindowMagnificationConnection.Stub {

    private static final String TAG = "WindowMagnificationConnectionImpl";

    private IWindowMagnificationConnectionCallback mConnectionCallback;
    private final Magnification mMagnification;
    private final Handler mHandler;

    WindowMagnificationConnectionImpl(@NonNull Magnification magnification,
            @Main Handler mainHandler) {
        mMagnification = magnification;
        mHandler = mainHandler;
    }

    @Override
    public void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
            IRemoteMagnificationAnimationCallback callback) {
        mHandler.post(
                () -> mMagnification.enableWindowMagnification(displayId, scale, centerX,
                        centerY, magnificationFrameOffsetRatioX,
                        magnificationFrameOffsetRatioY, callback));
    }

    @Override
    public void setScale(int displayId, float scale) {
        mHandler.post(() -> mMagnification.setScale(displayId, scale));
    }

    @Override
    public void disableWindowMagnification(int displayId,
            IRemoteMagnificationAnimationCallback callback) {
        mHandler.post(() -> mMagnification.disableWindowMagnification(displayId,
                callback));
    }

    @Override
    public void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        mHandler.post(
                () -> mMagnification.moveWindowMagnifier(displayId, offsetX, offsetY));
    }

    @Override
    public void moveWindowMagnifierToPosition(int displayId, float positionX, float positionY,
            IRemoteMagnificationAnimationCallback callback) {
        mHandler.post(() -> mMagnification.moveWindowMagnifierToPositionInternal(
                displayId, positionX, positionY, callback));
    }

    @Override
    public void showMagnificationButton(int displayId, int magnificationMode) {
        mHandler.post(
                () -> mMagnification.showMagnificationButton(displayId, magnificationMode));
    }

    @Override
    public void removeMagnificationButton(int displayId) {
        mHandler.post(
                () -> mMagnification.removeMagnificationButton(displayId));
    }

    @Override
    public void removeMagnificationSettingsPanel(int display) {
        mHandler.post(() -> mMagnification.hideMagnificationSettingsPanel(display));
    }

    @Override
    public void onUserMagnificationScaleChanged(int userId, int displayId, float scale) {
        mHandler.post(() -> mMagnification.setUserMagnificationScale(
                userId, displayId, scale));
    }

    @Override
    public void setConnectionCallback(IWindowMagnificationConnectionCallback callback) {
        mConnectionCallback = callback;
    }

    void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
        if (mConnectionCallback != null) {
            try {
                mConnectionCallback.onWindowMagnifierBoundsChanged(displayId, frame);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to inform bounds changed", e);
            }
        }
    }

    void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
        if (mConnectionCallback != null) {
            try {
                mConnectionCallback.onSourceBoundsChanged(displayId, sourceBounds);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to inform source bounds changed", e);
            }
        }
    }

    void onPerformScaleAction(int displayId, float scale, boolean updatePersistence) {
        if (mConnectionCallback != null) {
            try {
                mConnectionCallback.onPerformScaleAction(displayId, scale, updatePersistence);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to inform performing scale action", e);
            }
        }
    }

    void onAccessibilityActionPerformed(int displayId) {
        if (mConnectionCallback != null) {
            try {
                mConnectionCallback.onAccessibilityActionPerformed(displayId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to inform an accessibility action is already performed", e);
            }
        }
    }

    void onChangeMagnificationMode(int displayId, int mode) {
        if (mConnectionCallback != null) {
            try {
                mConnectionCallback.onChangeMagnificationMode(displayId, mode);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to inform changing magnification mode", e);
            }
        }
    }

    void onMove(int displayId) {
        if (mConnectionCallback != null) {
            try {
                mConnectionCallback.onMove(displayId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to inform taking control by a user", e);
            }
        }
    }
}
