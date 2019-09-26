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

import static com.google.common.base.Preconditions.checkState;

import android.annotation.Nullable;
import android.app.backup.BackupDataInput;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shadow for BackupDataInput. */
@Implements(BackupDataInput.class)
public class ShadowBackupDataInput {
    private static final List<DataEntity> ENTITIES = new ArrayList<>();
    @Nullable private static IOException sReadNextHeaderException;

    @Nullable private ByteArrayInputStream mCurrentEntityInputStream;
    private int mCurrentEntity = -1;

    /** Resets the shadow, clearing any entities or exception. */
    public static void reset() {
        ENTITIES.clear();
        sReadNextHeaderException = null;
    }

    /** Sets the exception which the input will throw for any call to {@link #readNextHeader}. */
    public static void setReadNextHeaderException(@Nullable IOException readNextHeaderException) {
        ShadowBackupDataInput.sReadNextHeaderException = readNextHeaderException;
    }

    /** Adds the given entity to the input. */
    public static void addEntity(DataEntity e) {
        ENTITIES.add(e);
    }

    /** Adds an entity to the input with the given key and value. */
    public static void addEntity(String key, byte[] value) {
        ENTITIES.add(new DataEntity(key, value, value.length));
    }

    public void __constructor__(FileDescriptor fd) {}

    @Implementation
    public boolean readNextHeader() throws IOException {
        if (sReadNextHeaderException != null) {
            throw sReadNextHeaderException;
        }

        mCurrentEntity++;

        if (mCurrentEntity >= ENTITIES.size()) {
            return false;
        }

        byte[] value = ENTITIES.get(mCurrentEntity).mValue;
        if (value == null) {
            mCurrentEntityInputStream = new ByteArrayInputStream(new byte[0]);
        } else {
            mCurrentEntityInputStream = new ByteArrayInputStream(value);
        }
        return true;
    }

    @Implementation
    public String getKey() {
        return ENTITIES.get(mCurrentEntity).mKey;
    }

    @Implementation
    public int getDataSize() {
        return ENTITIES.get(mCurrentEntity).mSize;
    }

    @Implementation
    public void skipEntityData() {
        // Do nothing.
    }

    @Implementation
    public int readEntityData(byte[] data, int offset, int size) {
        checkState(mCurrentEntityInputStream != null, "Must call readNextHeader() first");
        return mCurrentEntityInputStream.read(data, offset, size);
    }
}
