/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard.test_utils;

import android.content.ContentValues;

/**
 * ContentValues-like class which enables users to chain put() methods and restricts
 * the other methods.
 */
public class ContentValuesBuilder {
    private final ContentValues mContentValues;

    public ContentValuesBuilder(final ContentValues contentValues) {
        mContentValues = contentValues;
    }

    public ContentValuesBuilder put(String key, String value) {
        mContentValues.put(key, value);
        return this;
    }

    /*
    public ContentValuesBuilder put(String key, Byte value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Short value) {
        mContentValues.put(key, value);
        return this;
    }*/

    public ContentValuesBuilder put(String key, Integer value) {
        mContentValues.put(key, value);
        return this;
    }

    /*
    public ContentValuesBuilder put(String key, Long value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Float value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Double value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Boolean value) {
        mContentValues.put(key, value);
        return this;
    }*/

    public ContentValuesBuilder put(String key, byte[] value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder putNull(String key) {
        mContentValues.putNull(key);
        return this;
    }
}
