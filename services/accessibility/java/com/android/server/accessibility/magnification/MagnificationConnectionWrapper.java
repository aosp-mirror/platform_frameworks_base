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

import static android.accessibilityservice.AccessibilityTrace.FLAGS_MAGNIFICATION_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_MAGNIFICATION_CONNECTION_CALLBACK;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK;
import static android.os.IBinder.DeathRecipient;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.os.RemoteException;
import android.util.Slog;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IMagnificationConnectionCallback;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import com.android.server.accessibility.AccessibilityTraceManager;

/**
 * A wrapper of {@link IMagnificationConnection}.
 */
class MagnificationConnectionWrapper {

    private static final boolean DBG = false;
    private static final String TAG = "MagnificationConnectionWrapper";

    private final @NonNull IMagnificationConnection mConnection;
    private final @NonNull AccessibilityTraceManager mTrace;

    MagnificationConnectionWrapper(@NonNull IMagnificationConnection connection,
            @NonNull AccessibilityTraceManager trace) {
        mConnection = connection;
        mTrace = trace;
    }

    //Should not use this instance anymore after calling it.
    void unlinkToDeath(@NonNull DeathRecipient deathRecipient) {
        mConnection.asBinder().unlinkToDeath(deathRecipient, 0);
    }

    void linkToDeath(@NonNull DeathRecipient deathRecipient) throws RemoteException {
        mConnection.asBinder().linkToDeath(deathRecipient, 0);
    }

    boolean onFullscreenMagnificationActivationChanged(int displayId, boolean activated) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".onFullscreenMagnificationActivationChanged",
                    FLAGS_MAGNIFICATION_CONNECTION);
        }
        try {
            mConnection.onFullscreenMagnificationActivationChanged(displayId, activated);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling onFullscreenMagnificationActivationChanged");
            }
            return false;
        }
        return true;
    }

    boolean enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
            @Nullable MagnificationAnimationCallback callback) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".enableWindowMagnification",
                    FLAGS_MAGNIFICATION_CONNECTION,
                    "displayId=" + displayId + ";scale=" + scale + ";centerX=" + centerX
                            + ";centerY=" + centerY + ";magnificationFrameOffsetRatioX="
                            + magnificationFrameOffsetRatioX + ";magnificationFrameOffsetRatioY="
                            + magnificationFrameOffsetRatioY + ";callback=" + callback);
        }
        try {
            mConnection.enableWindowMagnification(displayId, scale, centerX, centerY,
                    magnificationFrameOffsetRatioX, magnificationFrameOffsetRatioY,
                    transformToRemoteCallback(callback, mTrace));
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling enableWindowMagnification()", e);
            }
            return false;
        }
        return true;
    }

    boolean setScaleForWindowMagnification(int displayId, float scale) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".setScale", FLAGS_MAGNIFICATION_CONNECTION,
                    "displayId=" + displayId + ";scale=" + scale);
        }
        try {
            mConnection.setScaleForWindowMagnification(displayId, scale);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling setScaleForWindowMagnification()", e);
            }
            return false;
        }
        return true;
    }

    boolean disableWindowMagnification(int displayId,
            @Nullable MagnificationAnimationCallback callback) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".disableWindowMagnification",
                    FLAGS_MAGNIFICATION_CONNECTION,
                    "displayId=" + displayId + ";callback=" + callback);
        }
        try {
            mConnection.disableWindowMagnification(displayId,
                    transformToRemoteCallback(callback, mTrace));
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling disableWindowMagnification()", e);
            }
            return false;
        }
        return true;
    }

    boolean moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".moveWindowMagnifier", FLAGS_MAGNIFICATION_CONNECTION,
                    "displayId=" + displayId + ";offsetX=" + offsetX + ";offsetY=" + offsetY);
        }
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

    boolean moveWindowMagnifierToPosition(int displayId, float positionX, float positionY,
            @Nullable MagnificationAnimationCallback callback) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".moveWindowMagnifierToPosition",
                    FLAGS_MAGNIFICATION_CONNECTION, "displayId=" + displayId
                            + ";positionX=" + positionX + ";positionY=" + positionY);
        }
        try {
            mConnection.moveWindowMagnifierToPosition(displayId, positionX, positionY,
                    transformToRemoteCallback(callback, mTrace));
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling moveWindowMagnifierToPosition()", e);
            }
            return false;
        }
        return true;
    }

    boolean showMagnificationButton(int displayId, int magnificationMode) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".showMagnificationButton",
                    FLAGS_MAGNIFICATION_CONNECTION,
                    "displayId=" + displayId + ";mode=" + magnificationMode);
        }
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
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".removeMagnificationButton",
                    FLAGS_MAGNIFICATION_CONNECTION, "displayId=" + displayId);
        }
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

    boolean removeMagnificationSettingsPanel(int displayId) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".removeMagnificationSettingsPanel",
                    FLAGS_MAGNIFICATION_CONNECTION, "displayId=" + displayId);
        }
        try {
            mConnection.removeMagnificationSettingsPanel(displayId);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling removeMagnificationSettingsPanel()", e);
            }
            return false;
        }
        return true;
    }

    boolean onUserMagnificationScaleChanged(int userId, int displayId, float scale) {
        if (mTrace.isA11yTracingEnabledForTypes(FLAGS_MAGNIFICATION_CONNECTION)) {
            mTrace.logTrace(TAG + ".onMagnificationScaleUpdated",
                    FLAGS_MAGNIFICATION_CONNECTION, "displayId=" + displayId);
        }
        try {
            mConnection.onUserMagnificationScaleChanged(userId, displayId, scale);
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Error calling onMagnificationScaleUpdated()", e);
            }
            return false;
        }
        return true;
    }

    boolean setConnectionCallback(IMagnificationConnectionCallback connectionCallback) {
        if (mTrace.isA11yTracingEnabledForTypes(
                FLAGS_MAGNIFICATION_CONNECTION
                | FLAGS_MAGNIFICATION_CONNECTION_CALLBACK)) {
            mTrace.logTrace(TAG + ".setConnectionCallback",
                    FLAGS_MAGNIFICATION_CONNECTION
                    | FLAGS_MAGNIFICATION_CONNECTION_CALLBACK,
                    "callback=" + connectionCallback);
        }
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
            MagnificationAnimationCallback callback, AccessibilityTraceManager trace) {
        if (callback == null) {
            return null;
        }
        return new RemoteAnimationCallback(callback, trace);
    }

    private static class RemoteAnimationCallback extends
            IRemoteMagnificationAnimationCallback.Stub {
        private final MagnificationAnimationCallback mCallback;
        private final AccessibilityTraceManager mTrace;

        RemoteAnimationCallback(@NonNull MagnificationAnimationCallback callback,
                               @NonNull AccessibilityTraceManager trace) {
            mCallback = callback;
            mTrace = trace;
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK)) {
                mTrace.logTrace("RemoteAnimationCallback.constructor",
                        FLAGS_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK, "callback=" + callback);
            }
        }

        @RequiresNoPermission
        @Override
        public void onResult(boolean success) throws RemoteException {
            mCallback.onResult(success);
            if (mTrace.isA11yTracingEnabledForTypes(
                    FLAGS_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK)) {
                mTrace.logTrace("RemoteAnimationCallback.onResult",
                        FLAGS_REMOTE_MAGNIFICATION_ANIMATION_CALLBACK, "success=" + success);
            }

        }
    }
}
