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
 * limitations under the License.
 */

package com.android.internal.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Helper class to initiate a screen recording
 */
public class ScreenRecordHelper {
    private static final String SYSUI_PACKAGE = "com.android.systemui";
    private static final String SYSUI_SCREENRECORD_LAUNCHER =
            "com.android.systemui.screenrecord.ScreenRecordDialog";

    private final Context mContext;

    /**
     * Create a new ScreenRecordHelper for the given context
     * @param context
     */
    public ScreenRecordHelper(Context context) {
        mContext = context;
    }

    /**
     * Show dialog of screen recording options to user.
     */
    public void launchRecordPrompt() {
        final ComponentName launcherComponent = new ComponentName(SYSUI_PACKAGE,
                SYSUI_SCREENRECORD_LAUNCHER);
        final Intent intent = new Intent();
        intent.setComponent(launcherComponent);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }
}
