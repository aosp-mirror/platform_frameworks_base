/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs;

import android.database.ContentObserver;
import android.os.Handler;

import com.android.systemui.statusbar.policy.Listenable;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.settings.SystemSettings;
import com.android.systemui.util.settings.UserSettingsProxy;

/**
 * Helper for managing secure and system settings through use of {@link UserSettingsProxy},
 * which is the common superclass of {@link SecureSettings} and {@link SystemSettings}.
 */
public abstract class UserSettingObserver extends ContentObserver implements Listenable {
    private final UserSettingsProxy mSettingsProxy;
    private final String mSettingName;
    private final int mDefaultValue;

    private boolean mListening;
    private int mUserId;
    private int mObservedValue;

    protected abstract void handleValueChanged(int value, boolean observedChange);

    public UserSettingObserver(UserSettingsProxy settingsProxy, Handler handler, String settingName,
            int userId) {
        this(settingsProxy, handler, settingName, userId, 0);
    }

    public UserSettingObserver(UserSettingsProxy settingsProxy, Handler handler, String settingName,
            int userId, int defaultValue) {
        super(handler);
        mSettingsProxy = settingsProxy;
        mSettingName = settingName;
        mObservedValue = mDefaultValue = defaultValue;
        mUserId = userId;
    }

    public int getValue() {
        return mListening ? mObservedValue : getValueFromProvider();
    }

    /**
     * Set the value of the observed setting.
     *
     * @param value The new value for the setting.
     */
    public void setValue(int value) {
        mSettingsProxy.putIntForUser(mSettingName, value, mUserId);
    }

    private int getValueFromProvider() {
        return mSettingsProxy.getIntForUser(mSettingName, mDefaultValue, mUserId);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening == mListening) return;
        mListening = listening;
        if (listening) {
            mObservedValue = getValueFromProvider();
            mSettingsProxy.registerContentObserverForUser(
                    mSettingsProxy.getUriFor(mSettingName), false, this, mUserId);
        } else {
            mSettingsProxy.unregisterContentObserver(this);
            mObservedValue = mDefaultValue;
        }
    }

    @Override
    public void onChange(boolean selfChange) {
        final int value = getValueFromProvider();
        final boolean changed = value != mObservedValue;
        mObservedValue = value;
        handleValueChanged(value, changed);
    }

    /**
     * Set user handle for which to observe the setting.
     */
    public void setUserId(int userId) {
        mUserId = userId;
        if (mListening) {
            setListening(false);
            setListening(true);
        }
    }

    public int getCurrentUser() {
        return mUserId;
    }

    public String getKey() {
        return mSettingName;
    }

    public boolean isListening() {
        return mListening;
    }
}
