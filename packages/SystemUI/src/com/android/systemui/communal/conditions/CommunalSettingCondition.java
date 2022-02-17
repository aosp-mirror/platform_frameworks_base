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

package com.android.systemui.communal.conditions;

import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.MainThread;

import com.android.systemui.util.condition.Condition;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/**
 * Monitors the communal setting, and informs any listeners with updates.
 */
public class CommunalSettingCondition extends Condition {
    private final SecureSettings mSecureSettings;
    private final ContentObserver mCommunalSettingContentObserver;

    @Inject
    public CommunalSettingCondition(@MainThread Handler mainHandler,
            SecureSettings secureSettings) {
        mSecureSettings = secureSettings;

        mCommunalSettingContentObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final boolean communalSettingEnabled = mSecureSettings.getIntForUser(
                        Settings.Secure.COMMUNAL_MODE_ENABLED, 0, UserHandle.USER_SYSTEM) == 1;
                updateCondition(communalSettingEnabled);
            }
        };
    }

    @Override
    protected void start() {
        mSecureSettings.registerContentObserverForUser(Settings.Secure.COMMUNAL_MODE_ENABLED,
                false /*notifyForDescendants*/, mCommunalSettingContentObserver,
                UserHandle.USER_SYSTEM);

        // Fetches setting immediately.
        mCommunalSettingContentObserver.onChange(false);
    }

    @Override
    protected void stop() {
        mSecureSettings.unregisterContentObserver(mCommunalSettingContentObserver);
    }
}
