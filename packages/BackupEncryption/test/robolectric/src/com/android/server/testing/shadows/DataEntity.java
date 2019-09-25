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

package com.android.server.testing.shadows;

import static com.google.common.base.Preconditions.checkNotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents a key value pair in {@link ShadowBackupDataInput} and {@link ShadowBackupDataOutput}.
 */
public class DataEntity {
    public final String mKey;
    public final byte[] mValue;
    public final int mSize;

    /**
     * Constructs a pair with a string value. The value will be converted to a byte array in {@link
     * StandardCharsets#UTF_8}.
     */
    public DataEntity(String key, String value) {
        this.mKey = checkNotNull(key);
        this.mValue = value.getBytes(StandardCharsets.UTF_8);
        mSize = this.mValue.length;
    }

    /**
     * Constructs a new entity with the given key but a negative size. This represents a deleted
     * pair.
     */
    public DataEntity(String key) {
        this.mKey = checkNotNull(key);
        mSize = -1;
        mValue = null;
    }

    /** Constructs a new entity where the size of the value is the entire array. */
    public DataEntity(String key, byte[] value) {
        this(key, value, value.length);
    }

    /**
     * Constructs a new entity.
     *
     * @param key the key of the pair
     * @param data the value to associate with the key
     * @param size the length of the value in bytes
     */
    public DataEntity(String key, byte[] data, int size) {
        this.mKey = checkNotNull(key);
        this.mSize = size;
        mValue = new byte[size];
        for (int i = 0; i < size; i++) {
            mValue[i] = data[i];
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DataEntity that = (DataEntity) o;

        if (mSize != that.mSize) {
            return false;
        }
        if (!mKey.equals(that.mKey)) {
            return false;
        }
        return Arrays.equals(mValue, that.mValue);
    }

    @Override
    public int hashCode() {
        int result = mKey.hashCode();
        result = 31 * result + Arrays.hashCode(mValue);
        result = 31 * result + mSize;
        return result;
    }
}
