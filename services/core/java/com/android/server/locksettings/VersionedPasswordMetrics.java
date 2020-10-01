/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.locksettings;

import android.app.admin.PasswordMetrics;

import com.android.internal.widget.LockscreenCredential;

import java.nio.ByteBuffer;

/**
 * A versioned and serializable wrapper around {@link PasswordMetrics},
 * for long-term persistence on disk.
 */
public class VersionedPasswordMetrics {
    private static final int VERSION_1 = 1;

    private final PasswordMetrics mMetrics;
    private final int mVersion;

    private VersionedPasswordMetrics(int version, PasswordMetrics metrics) {
        mMetrics = metrics;
        mVersion = version;
    }

    public VersionedPasswordMetrics(LockscreenCredential credential) {
        this(VERSION_1, PasswordMetrics.computeForCredential(credential));
    }

    public int getVersion() {
        return mVersion;
    }

    public PasswordMetrics getMetrics() {
        return mMetrics;
    }

    /** Serialize object to a byte array. */
    public byte[] serialize() {
        final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 11);
        buffer.putInt(mVersion);
        buffer.putInt(mMetrics.credType);
        buffer.putInt(mMetrics.length);
        buffer.putInt(mMetrics.letters);
        buffer.putInt(mMetrics.upperCase);
        buffer.putInt(mMetrics.lowerCase);
        buffer.putInt(mMetrics.numeric);
        buffer.putInt(mMetrics.symbols);
        buffer.putInt(mMetrics.nonLetter);
        buffer.putInt(mMetrics.nonNumeric);
        buffer.putInt(mMetrics.seqLength);
        return buffer.array();
    }

    /** Deserialize byte array to an object */
    public static VersionedPasswordMetrics deserialize(byte[] data) {
        final ByteBuffer buffer = ByteBuffer.allocate(data.length);
        buffer.put(data, 0, data.length);
        buffer.flip();
        final int version = buffer.getInt();
        PasswordMetrics metrics = new PasswordMetrics(buffer.getInt(), buffer.getInt(),
                buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(),
                buffer.getInt(), buffer.getInt(), buffer.getInt());
        return new VersionedPasswordMetrics(version, metrics);
    }
}
