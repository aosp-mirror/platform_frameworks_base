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
 * limitations under the License.
 */

package android.util;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;

/**
 * Abstract class for observing changes to a specified setting stored as a comma-separated key value
 * list of parameters. Registers and unregisters a {@link ContentObserver} and handles updates when
 * the setting changes.
 *
 * <p>Subclasses should pass in the relevant setting's {@link Uri} in the constructor and implement
 * {@link #update(KeyValueListParser)} to receive updates when the value changes.
 * Calls to {@link #update(KeyValueListParser)} only trigger after calling {@link
 * #start()}.
 *
 * <p>To get the most up-to-date parameter values, first call {@link #start()} before accessing the
 * values to start observing changes, and then call {@link #stop()} once finished.
 *
 * @hide
 */
public abstract class KeyValueSettingObserver {
    private static final String TAG = "KeyValueSettingObserver";

    private final KeyValueListParser mParser = new KeyValueListParser(',');

    private final ContentObserver mObserver;
    private final ContentResolver mResolver;
    private final Uri mSettingUri;

    public KeyValueSettingObserver(Handler handler, ContentResolver resolver,
            Uri uri) {
        mObserver = new SettingObserver(handler);
        mResolver = resolver;
        mSettingUri = uri;
    }

    /** Starts observing changes for the setting. Pair with {@link #stop()}. */
    public void start() {
        mResolver.registerContentObserver(mSettingUri, false, mObserver);
        setParserValue();
        update(mParser);
    }

    /** Stops observing changes for the setting. */
    public void stop() {
        mResolver.unregisterContentObserver(mObserver);
    }

    /**
     * Returns the {@link String} representation of the setting. Subclasses should implement this
     * for their setting.
     */
    public abstract String getSettingValue(ContentResolver resolver);

    /** Updates the parser with the current setting value. */
    private void setParserValue() {
        String setting = getSettingValue(mResolver);
        try {
            mParser.setString(setting);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Malformed setting: " + setting);
        }
    }

    /** Subclasses should implement this to update references to their parameters. */
    public abstract void update(KeyValueListParser parser);

    private class SettingObserver extends ContentObserver {
        private SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            setParserValue();
            update(mParser);
        }
    }
}
