/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import javax.inject.Inject;

/**
 * Starts a configurable runnable on timeout. Can be cancelled. Used for automatically dismissing
 * floating overlays.
 */
public class TimeoutHandler extends Handler {
    private static final String TAG = "TimeoutHandler";

    private static final int MESSAGE_CORNER_TIMEOUT = 2;
    private static final int DEFAULT_TIMEOUT_MILLIS = 6000;

    private final Context mContext;

    private Runnable mOnTimeout;
    int mDefaultTimeout = DEFAULT_TIMEOUT_MILLIS;

    @Inject
    public TimeoutHandler(Context context) {
        super(Looper.getMainLooper());
        mContext = context;
        mOnTimeout = () -> {
        };
    }

    public void setOnTimeoutRunnable(Runnable onTimeout) {
        mOnTimeout = onTimeout;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_CORNER_TIMEOUT:
                mOnTimeout.run();
                break;
            default:
                break;
        }
    }

    /**
     * Set the default timeout (if not overridden by accessibility)
     */
    public void setDefaultTimeoutMillis(int timeout) {
        mDefaultTimeout = timeout;
    }

    int getDefaultTimeoutMillis() {
        return mDefaultTimeout;
    }

    /**
     * Cancel the current timeout, if any. To reset the delayed runnable use resetTimeout instead.
     */
    public void cancelTimeout() {
        if (DEBUG_DISMISS) {
            Log.d(TAG, "cancel timeout");
        }
        removeMessages(MESSAGE_CORNER_TIMEOUT);
    }

    /**
     * Reset the timeout.
     */
    public void resetTimeout() {
        cancelTimeout();

        AccessibilityManager accessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        long timeoutMs = accessibilityManager.getRecommendedTimeoutMillis(
                mDefaultTimeout,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);

        sendMessageDelayed(obtainMessage(MESSAGE_CORNER_TIMEOUT), timeoutMs);
        if (DEBUG_DISMISS) {
            Log.d(TAG, "dismiss timeout: " + timeoutMs + " ms");
        }
    }
}
