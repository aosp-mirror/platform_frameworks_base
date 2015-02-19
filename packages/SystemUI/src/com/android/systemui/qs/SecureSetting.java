/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings.Secure;

import com.android.systemui.statusbar.policy.Listenable;

/** Helper for managing a secure setting. **/
public abstract class SecureSetting extends ContentObserver implements Listenable {
    private static final int DEFAULT = 0;

    private final Context mContext;
    private final String mSettingName;

    private boolean mListening;
    private int mUserId;
    private int mObservedValue = DEFAULT;

    protected abstract void handleValueChanged(int value, boolean observedChange);

    public SecureSetting(Context context, Handler handler, String settingName) {
        super(handler);
        mContext = context;
        mSettingName = settingName;
        mUserId = ActivityManager.getCurrentUser();
    }

    public int getValue() {
        return Secure.getIntForUser(mContext.getContentResolver(), mSettingName, DEFAULT, mUserId);
    }

    public void setValue(int value) {
        Secure.putIntForUser(mContext.getContentResolver(), mSettingName, value, mUserId);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening == mListening) return;
        mListening = listening;
        if (listening) {
            mObservedValue = getValue();
            mContext.getContentResolver().registerContentObserver(
                    Secure.getUriFor(mSettingName), false, this, mUserId);
        } else {
            mContext.getContentResolver().unregisterContentObserver(this);
            mObservedValue = DEFAULT;
        }
    }

    @Override
    public void onChange(boolean selfChange) {
        final int value = getValue();
        handleValueChanged(value, value != mObservedValue);
        mObservedValue = value;
    }

    public void setUserId(int userId) {
        mUserId = userId;
        if (mListening) {
            setListening(false);
            setListening(true);
        }
    }
}
