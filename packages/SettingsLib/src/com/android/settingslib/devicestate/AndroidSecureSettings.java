/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.devicestate;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.provider.Settings;

/**
 * Implementation of {@link SecureSettings} that uses Android's {@link Settings.Secure}
 * implementation.
 */
class AndroidSecureSettings implements SecureSettings {

    private final ContentResolver mContentResolver;

    AndroidSecureSettings(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    @Override
    public void putStringForUser(String name, String value, int userHandle) {
        Settings.Secure.putStringForUser(mContentResolver, name, value, userHandle);
    }

    @Override
    public String getStringForUser(String name, int userHandle) {
        return Settings.Secure.getStringForUser(mContentResolver, name, userHandle);
    }

    @Override
    public void registerContentObserver(String name, boolean notifyForDescendants,
            ContentObserver observer, int userHandle) {
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(name),
                notifyForDescendants,
                observer,
                userHandle);
    }
}
