/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.autofill.ui;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;

/**
 * This class controls showing/hiding overlays. We don't
 * hide all overlays (toast/system alerts) while sensitive
 * autofill UI is up.
 */
class OverlayControl {

    private final IBinder mToken = new Binder();

    private final @NonNull AppOpsManager mAppOpsManager;

    OverlayControl(@NonNull Context context) {
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
    }

    void hideOverlays() {
        setOverlayAllowed(false);
    }

    void showOverlays() {
        setOverlayAllowed(true);
    }

    private void setOverlayAllowed(boolean allowed) {
        if (mAppOpsManager != null) {
            mAppOpsManager.setUserRestrictionForUser(AppOpsManager.OP_SYSTEM_ALERT_WINDOW, !allowed,
                    mToken, null, UserHandle.USER_ALL);
            mAppOpsManager.setUserRestrictionForUser(AppOpsManager.OP_TOAST_WINDOW, !allowed,
                    mToken, null, UserHandle.USER_ALL);
        }
    }
}
