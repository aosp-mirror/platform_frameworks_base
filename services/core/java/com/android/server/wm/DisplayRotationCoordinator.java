/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Singleton for coordinating rotation across multiple displays. Used to notify non-default
 * displays when the default display rotates.
 *
 * Note that this class does not need locking because it is always protected by WindowManagerService
 * mGlobalLock.
 */
class DisplayRotationCoordinator {

    private static final String TAG = "DisplayRotationCoordinator";

    @Surface.Rotation
    private int mDefaultDisplayDefaultRotation;

    @Nullable
    @VisibleForTesting
    Runnable mDefaultDisplayRotationChangedCallback;
    private int mCallbackDisplayId = Display.INVALID_DISPLAY;

    @Surface.Rotation
    private int mDefaultDisplayCurrentRotation;

    /**
     * Notifies clients when the default display rotation changes.
     */
    void onDefaultDisplayRotationChanged(@Surface.Rotation int rotation) {
        mDefaultDisplayCurrentRotation = rotation;

        if (mDefaultDisplayRotationChangedCallback != null) {
            mDefaultDisplayRotationChangedCallback.run();
        }
    }

    void setDefaultDisplayDefaultRotation(@Surface.Rotation int rotation) {
        mDefaultDisplayDefaultRotation = rotation;
    }

    @Surface.Rotation
    int getDefaultDisplayCurrentRotation() {
        return mDefaultDisplayCurrentRotation;
    }

    /**
     * Register a callback to be notified when the default display's rotation changes. Clients can
     * query the default display's current rotation via {@link #getDefaultDisplayCurrentRotation()}.
     */
    void setDefaultDisplayRotationChangedCallback(int displayId, @NonNull Runnable callback) {
        if (mDefaultDisplayRotationChangedCallback != null && displayId != mCallbackDisplayId) {
            throw new UnsupportedOperationException("Multiple clients unsupported"
                    + ". Incoming displayId: " + displayId
                    + ", existing displayId: " + mCallbackDisplayId);
        }

        mDefaultDisplayRotationChangedCallback = callback;
        mCallbackDisplayId = displayId;

        if (mDefaultDisplayCurrentRotation != mDefaultDisplayDefaultRotation) {
            callback.run();
        }
    }

    /**
     * Removes the callback that was added via
     * {@link #setDefaultDisplayRotationChangedCallback(int, Runnable)}.
     */
    void removeDefaultDisplayRotationChangedCallback(@NonNull Runnable callback) {
        if (callback != mDefaultDisplayRotationChangedCallback) {
            Slog.w(TAG, "Attempted to remove non-matching callback."
                    + " DisplayId: " + mCallbackDisplayId);
            return;
        }

        mDefaultDisplayRotationChangedCallback = null;
        mCallbackDisplayId = Display.INVALID_DISPLAY;
    }

    static boolean isSecondaryInternalDisplay(@NonNull DisplayContent displayContent) {
        if (displayContent.isDefaultDisplay) {
            return false;
        } else if (displayContent.mDisplay == null) {
            return false;
        }
        return displayContent.mDisplay.getType() == Display.TYPE_INTERNAL;
    }
}
