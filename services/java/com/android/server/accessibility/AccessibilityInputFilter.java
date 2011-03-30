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
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy;

/**
 * Input filter for accessibility.
 *
 * Currently just a stub but will eventually implement touch exploration, etc.
 */
public class AccessibilityInputFilter extends InputFilter {
    private static final String TAG = "AccessibilityInputFilter";
    private static final boolean DEBUG = true;

    private final Context mContext;

    public AccessibilityInputFilter(Context context) {
        super(context.getMainLooper());
        mContext = context;
    }

    @Override
    public void onInstalled() {
        if (DEBUG) {
            Slog.d(TAG, "Accessibility input filter installed.");
        }
        super.onInstalled();
    }

    @Override
    public void onUninstalled() {
        if (DEBUG) {
            Slog.d(TAG, "Accessibility input filter uninstalled.");
        }
        super.onUninstalled();
    }

    @Override
    public void onInputEvent(InputEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.d(TAG, "Accessibility input filter received input event: "
                    + event + ", policyFlags=0x" + Integer.toHexString(policyFlags));
        }

        // To prove that this is working as intended, we will silently transform
        // Q key presses into non-repeating Z's as part of this stub implementation.
        // TODO: Replace with the real thing.
        if (event instanceof KeyEvent) {
            final KeyEvent keyEvent = (KeyEvent)event;
            if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_Q) {
                if (keyEvent.getRepeatCount() == 0) {
                    sendInputEvent(new KeyEvent(keyEvent.getDownTime(), keyEvent.getEventTime(),
                            keyEvent.getAction(), KeyEvent.KEYCODE_Z, keyEvent.getRepeatCount(),
                            keyEvent.getMetaState(), keyEvent.getDeviceId(), keyEvent.getScanCode(),
                            keyEvent.getFlags(), keyEvent.getSource()),
                            policyFlags | WindowManagerPolicy.FLAG_DISABLE_KEY_REPEAT);
                }
                return;
            }
        }

        super.onInputEvent(event, policyFlags);
    }
}
