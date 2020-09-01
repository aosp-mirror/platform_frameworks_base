/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wallpaperbackup.utils;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

public class ContextWithServiceOverrides extends ContextWrapper {
    private static final String TAG = "ContextWithOverrides";

    private Map<String, Object> mInjectedSystemServices = new HashMap<>();
    private SharedPreferences mSharedPreferencesOverride;

    public ContextWithServiceOverrides(Context base) {
        super(base);
    }

    public <S> void injectSystemService(Class<S> cls, S service) {
        final String name = getSystemServiceName(cls);
        mInjectedSystemServices.put(name, service);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Object getSystemService(String name) {
        if (mInjectedSystemServices.containsKey(name)) {
            return mInjectedSystemServices.get(name);
        }
        return super.getSystemService(name);
    }

    public void setSharedPreferencesOverride(SharedPreferences override) {
        mSharedPreferencesOverride = override;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return mSharedPreferencesOverride == null
                ? super.getSharedPreferences(name, mode)
                : mSharedPreferencesOverride;
    }
}
