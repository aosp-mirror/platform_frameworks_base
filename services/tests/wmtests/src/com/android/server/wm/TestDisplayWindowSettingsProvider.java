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

package com.android.server.wm;

import android.annotation.NonNull;
import android.view.DisplayInfo;

import java.util.HashMap;
import java.util.Map;

import com.android.server.wm.DisplayWindowSettingsProvider.WritableSettingsStorage;
import com.android.server.wm.DisplayWindowSettings.SettingsProvider.SettingsEntry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * In-memory DisplayWindowSettingsProvider used in tests. Ensures no settings are read from or
 * written to device-specific display settings files.
 */
public final class TestDisplayWindowSettingsProvider extends DisplayWindowSettingsProvider {

    private final Map<String, SettingsEntry> mOverrideSettingsMap = new HashMap<>();

    public TestDisplayWindowSettingsProvider() {
        super(new TestStorage(), new TestStorage());
    }

    @Override
    @NonNull
    public SettingsEntry getSettings(@NonNull DisplayInfo info) {
        // Because no settings are read from settings files, there is no need to store base
        // settings. Only override settings are necessary to track because they can be modified
        // during tests (e.g. display size, ignore orientation requests).
        return getOverrideSettings(info);
    }

    @Override
    @NonNull
    public SettingsEntry getOverrideSettings(@NonNull DisplayInfo info) {
        return new SettingsEntry(getOrCreateOverrideSettingsEntry(info));
    }

    @Override
    public void updateOverrideSettings(@NonNull DisplayInfo info,
            @NonNull SettingsEntry overrides) {
        final SettingsEntry overrideSettings = getOrCreateOverrideSettingsEntry(info);
        overrideSettings.setTo(overrides);
    }

    @Override
    public void onDisplayRemoved(@NonNull DisplayInfo info) {
        final String identifier = getIdentifier(info);
        mOverrideSettingsMap.remove(identifier);
    }

    @NonNull
    private SettingsEntry getOrCreateOverrideSettingsEntry(DisplayInfo info) {
        final String identifier = getIdentifier(info);
        SettingsEntry settings;
        if ((settings = mOverrideSettingsMap.get(identifier)) != null) {
            return settings;
        }
        settings = new SettingsEntry();
        mOverrideSettingsMap.put(identifier, settings);
        return settings;
    }

    /**
     * In {@link TestDisplayWindowSettingsProvider}, always use uniqueId as the identifier.
     */
    private static String getIdentifier(DisplayInfo displayInfo) {
        return displayInfo.uniqueId;
    }

    /** In-memory storage implementation. */
    public static class TestStorage implements WritableSettingsStorage {
        private InputStream mReadStream;
        private ByteArrayOutputStream mWriteStream;

        private boolean mWasSuccessful;

        /**
         * Returns input stream for reading. By default tries forward the output stream if previous
         * write was successful.
         * @see #closeRead()
         */
        @Override
        public InputStream openRead() throws FileNotFoundException {
            if (mReadStream == null && mWasSuccessful) {
                mReadStream = new ByteArrayInputStream(mWriteStream.toByteArray());
            }
            if (mReadStream == null) {
                throw new FileNotFoundException();
            }
            if (mReadStream.markSupported()) {
                mReadStream.mark(Integer.MAX_VALUE);
            }
            return mReadStream;
        }

        /** Must be called after each {@link #openRead} to reset the position in the stream. */
        public void closeRead() throws IOException {
            if (mReadStream == null) {
                throw new FileNotFoundException();
            }
            if (mReadStream.markSupported()) {
                mReadStream.reset();
            }
            mReadStream = null;
        }

        /**
         * Creates new or resets existing output stream for write. Automatically closes previous
         * read stream, since following reads should happen based on this new write.
         */
        @Override
        public OutputStream startWrite() throws IOException {
            if (mWriteStream == null) {
                mWriteStream = new ByteArrayOutputStream();
            } else {
                mWriteStream.reset();
            }
            if (mReadStream != null) {
                closeRead();
            }
            return mWriteStream;
        }

        @Override
        public void finishWrite(OutputStream os, boolean success) {
            mWasSuccessful = success;
            try {
                os.close();
            } catch (IOException e) {
                // This method can't throw IOException since the super implementation doesn't, so
                // we just wrap it in a RuntimeException so we end up crashing the test all the
                // same.
                throw new RuntimeException(e);
            }
        }

        /** Overrides the read stream of the injector. By default it uses current write stream. */
        public void setReadStream(InputStream is) {
            mReadStream = is;
        }

        public boolean wasWriteSuccessful() {
            return mWasSuccessful;
        }
    }
}
