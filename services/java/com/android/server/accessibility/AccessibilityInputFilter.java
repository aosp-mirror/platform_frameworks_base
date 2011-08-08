/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.accessibility;

import com.android.server.wm.InputFilter;

import android.content.Context;
import android.util.Slog;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy;

/**
 * Input filter for accessibility.
 *
 * Currently just a stub but will eventually implement touch exploration, etc.
 */
public class AccessibilityInputFilter extends InputFilter {
    private static final String TAG = "AccessibilityInputFilter";
    private static final boolean DEBUG = false;

    private final Context mContext;

    /**
     * This is an interface for explorers that take a {@link MotionEvent}
     * stream and perform touch exploration of the screen content.
     */
    public interface Explorer {
        /**
         * Handles a {@link MotionEvent}.
         *
         * @param event The event to handle.
         * @param policyFlags The policy flags associated with the event.
         */
        public void onMotionEvent(MotionEvent event, int policyFlags);

        /**
         * Requests that the explorer clears its internal state.
         *
         * @param event The last received event.
         * @param policyFlags The policy flags associated with the event.
         */
        public void clear(MotionEvent event, int policyFlags);

        /**
         * Requests that the explorer clears its internal state.
         */
        public void clear();
    }

    private TouchExplorer mTouchExplorer;
    private int mTouchscreenSourceDeviceId;

    public AccessibilityInputFilter(Context context) {
        super(context.getMainLooper());
        mContext = context;
    }

    @Override
    public void onInstalled() {
        if (DEBUG) {
            Slog.d(TAG, "Accessibility input filter installed.");
        }
        mTouchExplorer = new TouchExplorer(this, mContext);
        super.onInstalled();
    }

    @Override
    public void onUninstalled() {
        if (DEBUG) {
            Slog.d(TAG, "Accessibility input filter uninstalled.");
        }
        mTouchExplorer.clear();
        super.onUninstalled();
    }

    @Override
    public void onInputEvent(InputEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.d(TAG, "Received event: " + event + ", policyFlags=0x" 
                    + Integer.toHexString(policyFlags));
        }
        if (event.getSource() == InputDevice.SOURCE_TOUCHSCREEN) {
            MotionEvent motionEvent = (MotionEvent) event;
            int deviceId = event.getDeviceId();
            if (mTouchscreenSourceDeviceId != deviceId) {
                mTouchscreenSourceDeviceId = deviceId;
                mTouchExplorer.clear(motionEvent, policyFlags);
            }
            if ((policyFlags & WindowManagerPolicy.FLAG_PASS_TO_USER) != 0) {
                mTouchExplorer.onMotionEvent(motionEvent, policyFlags);
            } else {
                mTouchExplorer.clear(motionEvent, policyFlags);
            }
        } else {
            super.onInputEvent(event, policyFlags);
        }
    }
}
