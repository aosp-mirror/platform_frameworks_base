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
 * limitations under the License
 */

package com.android.internal.backup;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Slog;

class LocalTransportParameters {
    private static final String TAG = "LocalTransportParams";
    private static final String SETTING = Settings.Secure.BACKUP_LOCAL_TRANSPORT_PARAMETERS;
    private static final String KEY_FAKE_ENCRYPTION_FLAG = "fake_encryption_flag";

    private final KeyValueListParser mParser = new KeyValueListParser(',');
    private final ContentObserver mObserver;
    private final ContentResolver mResolver;
    private boolean mFakeEncryptionFlag;

    LocalTransportParameters(Handler handler, ContentResolver resolver) {
        mObserver = new Observer(handler);
        mResolver = resolver;
    }

    /** Observes for changes in the setting. This method MUST be paired with {@link #stop()}. */
    void start() {
        mResolver.registerContentObserver(Settings.Secure.getUriFor(SETTING), false, mObserver);
        update();
    }

    /** Stop observing for changes in the setting. */
    void stop() {
        mResolver.unregisterContentObserver(mObserver);
    }

    boolean isFakeEncryptionFlag() {
        return mFakeEncryptionFlag;
    }

    private void update() {
        String parameters = "";
        try {
            parameters = Settings.Secure.getString(mResolver, SETTING);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Malformed " + SETTING + " setting: " + e.getMessage());
        }
        mParser.setString(parameters);
        mFakeEncryptionFlag = mParser.getBoolean(KEY_FAKE_ENCRYPTION_FLAG, false);
    }

    private class Observer extends ContentObserver {
        private Observer(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }
    }
}
