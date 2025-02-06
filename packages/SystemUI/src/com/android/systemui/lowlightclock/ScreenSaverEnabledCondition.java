/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.lowlightclock;

import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.util.settings.SecureSettings;

import kotlinx.coroutines.CoroutineScope;

import javax.inject.Inject;

/**
 * Condition for monitoring if the screensaver setting is enabled.
 */
public class ScreenSaverEnabledCondition extends Condition {
    private static final String TAG = ScreenSaverEnabledCondition.class.getSimpleName();

    private final boolean mScreenSaverEnabledByDefaultConfig;
    private final SecureSettings mSecureSettings;

    private final ContentObserver mScreenSaverSettingObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            updateScreenSaverEnabledSetting();
        }
    };

    @Inject
    public ScreenSaverEnabledCondition(@Application CoroutineScope scope, @Main Resources resources,
            SecureSettings secureSettings) {
        super(scope);
        mScreenSaverEnabledByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mSecureSettings = secureSettings;
    }

    @Override
    protected void start() {
        mSecureSettings.registerContentObserverForUserSync(
                Settings.Secure.SCREENSAVER_ENABLED,
                mScreenSaverSettingObserver, UserHandle.USER_CURRENT);
        updateScreenSaverEnabledSetting();
    }

    @Override
    protected void stop() {
        mSecureSettings.unregisterContentObserverSync(mScreenSaverSettingObserver);
    }

    @Override
    protected int getStartStrategy() {
        return START_EAGERLY;
    }

    private void updateScreenSaverEnabledSetting() {
        final boolean enabled = mSecureSettings.getIntForUser(
                Settings.Secure.SCREENSAVER_ENABLED,
                mScreenSaverEnabledByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0;
        if (!enabled) {
            Log.i(TAG, "Disabling low-light clock because screen saver has been disabled");
        }
        updateCondition(enabled);
    }
}
