/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.util.Log;

import com.android.keyguard.KeyguardHostView.OnDismissAction;

/**
 * Executes actions that require the screen to be unlocked. Delegates the actual handling to an
 * implementation passed via {@link #setDismissHandler}.
 */
public class KeyguardDismissUtil implements KeyguardDismissHandler {
    private static final String TAG = "KeyguardDismissUtil";

    private volatile KeyguardDismissHandler mDismissHandler;

    /** Sets the actual {@link DismissHandler} implementation. */
    public void setDismissHandler(KeyguardDismissHandler dismissHandler) {
        mDismissHandler = dismissHandler;
    }

    /**
     * Executes an action that requres the screen to be unlocked.
     *
     * <p>Must be called after {@link #setDismissHandler}.
     */
    @Override
    public void executeWhenUnlocked(OnDismissAction action) {
        KeyguardDismissHandler dismissHandler = mDismissHandler;
        if (dismissHandler == null) {
            Log.wtf(TAG, "KeyguardDismissHandler not set.");
            action.onDismiss();
            return;
        }
        dismissHandler.executeWhenUnlocked(action);
    }
}
