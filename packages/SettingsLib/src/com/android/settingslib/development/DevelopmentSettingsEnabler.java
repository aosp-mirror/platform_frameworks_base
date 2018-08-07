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

package com.android.settingslib.development;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;

public class DevelopmentSettingsEnabler {

    public static final String DEVELOPMENT_SETTINGS_CHANGED_ACTION =
            "com.android.settingslib.development.DevelopmentSettingsEnabler.SETTINGS_CHANGED";

    private DevelopmentSettingsEnabler() {
    }

    public static void setDevelopmentSettingsEnabled(Context context, boolean enable) {
        Settings.Global.putInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, enable ? 1 : 0);
        LocalBroadcastManager.getInstance(context)
                .sendBroadcast(new Intent(DEVELOPMENT_SETTINGS_CHANGED_ACTION));
    }

    public static boolean isDevelopmentSettingsEnabled(Context context) {
        final UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        final boolean settingEnabled = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                Build.TYPE.equals("eng") ? 1 : 0) != 0;
        final boolean hasRestriction = um.hasUserRestriction(
                UserManager.DISALLOW_DEBUGGING_FEATURES);
        final boolean isAdminOrDemo = um.isAdminUser() || um.isDemoUser();
        return isAdminOrDemo && !hasRestriction && settingEnabled;
    }
}
