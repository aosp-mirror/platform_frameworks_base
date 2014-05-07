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

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings.Secure;

import com.android.systemui.statusbar.policy.Disposable;

/** Helper for managing a secure setting. **/
public abstract class SecureSetting extends ContentObserver implements Disposable {
    private final Context mContext;
    private final String mSettingName;

    protected abstract void handleValueChanged(int value);

    public SecureSetting(Context context, Handler handler, String settingName) {
        super(handler);
        mContext = context;
        mSettingName = settingName;
        rebindForCurrentUser();
    }

    public void rebindForCurrentUser() {
        mContext.getContentResolver().registerContentObserver(
                Secure.getUriFor(mSettingName), false, this);
    }

    public int getValue() {
        return Secure.getInt(mContext.getContentResolver(), mSettingName, 0);
    }

    public void setValue(int value) {
        Secure.putInt(mContext.getContentResolver(), mSettingName, value);
    }

    @Override
    public void dispose() {
        mContext.getContentResolver().unregisterContentObserver(this);
    }

    @Override
    public void onChange(boolean selfChange) {
        handleValueChanged(getValue());
    }
}
