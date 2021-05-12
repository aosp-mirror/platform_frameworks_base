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

package com.android.server.policy;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * This class wrapped the Intent for global key ops.
 */
public final class GlobalKeyIntent {
    private static final String EXTRA_BEGAN_FROM_NON_INTERACTIVE =
            "EXTRA_BEGAN_FROM_NON_INTERACTIVE";

    private final ComponentName mComponentName;
    private final KeyEvent mKeyEvent;
    private final boolean mBeganFromNonInteractive;

    GlobalKeyIntent(@NonNull ComponentName componentName, @NonNull KeyEvent event,
            boolean beganFromNonInteractive) {
        mComponentName = componentName;
        mKeyEvent = new KeyEvent(event);
        mBeganFromNonInteractive = beganFromNonInteractive;
    }

    Intent getIntent() {
        final Intent intent = new Intent(Intent.ACTION_GLOBAL_BUTTON)
                .setComponent(mComponentName)
                .setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intent.EXTRA_KEY_EVENT, mKeyEvent)
                .putExtra(EXTRA_BEGAN_FROM_NON_INTERACTIVE, mBeganFromNonInteractive);
        return intent;
    }

    /**
     * Get the {@link KeyEvent} information of {@link Intent#ACTION_GLOBAL_BUTTON}.
     */
    public KeyEvent getKeyEvent() {
        return mKeyEvent;
    }

    /**
     * Indicate if the global key is dispatched from non-interactive mode.
     * Information of {@link Intent#ACTION_GLOBAL_BUTTON}.
     */
    public boolean beganFromNonInteractive() {
        return mBeganFromNonInteractive;
    }

    /**
     * Generate a GlobalKeyIntent from {@link Intent}, the action must be
     * {@link Intent#ACTION_GLOBAL_BUTTON}.
     *
     * @param intent The received intent of the global key.
     */
    public static GlobalKeyIntent from(@NonNull Intent intent) {
        if (intent.getAction() != Intent.ACTION_GLOBAL_BUTTON) {
            Log.wtf("GlobalKeyIntent", "Intent should be ACTION_GLOBAL_BUTTON");
            return null;
        }

        final KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        final boolean fromNonInteractive =
                intent.getBooleanExtra(EXTRA_BEGAN_FROM_NON_INTERACTIVE, false);
        return new GlobalKeyIntent(intent.getComponent(), event, fromNonInteractive);
    }
}
