/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.security.keystore.recovery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WrappedApplicationKeyTest {

    private static final String ALIAS = "karlin";
    private static final byte[] KEY_MATERIAL = new byte[] { 0, 1, 2, 3, 4 };
    private static final byte[] METADATA = new byte[] {3, 2, 1, 0};

    private Parcel mParcel;

    @Before
    public void setUp() {
        mParcel = Parcel.obtain();
    }

    @After
    public void tearDown() {
        mParcel.recycle();
    }

    @Test
    public void build_setsAlias() {
        assertEquals(ALIAS, buildTestKey().getAlias());
    }

    @Test
    public void build_setsEncryptedKeyMaterial() {
        assertArrayEquals(KEY_MATERIAL, buildTestKey().getEncryptedKeyMaterial());
    }

    @Test
    public void build_setsMetadata_nonNull() {
        assertArrayEquals(METADATA, buildTestKeyWithMetadata(METADATA).getMetadata());
    }

    @Test
    public void build_setsMetadata_null() {
        assertArrayEquals(null, buildTestKeyWithMetadata(null).getMetadata());
    }

    @Test
    public void writeToParcel_writesAliasToParcel() {
        buildTestKeyWithMetadata(/*metadata=*/ null).writeToParcel(mParcel, /*flags=*/ 0);

        mParcel.setDataPosition(0);
        WrappedApplicationKey readFromParcel =
                WrappedApplicationKey.CREATOR.createFromParcel(mParcel);
        assertEquals(ALIAS, readFromParcel.getAlias());
    }

    @Test
    public void writeToParcel_writesKeyMaterial() {
        buildTestKeyWithMetadata(/*metadata=*/ null).writeToParcel(mParcel, /*flags=*/ 0);

        mParcel.setDataPosition(0);
        WrappedApplicationKey readFromParcel =
                WrappedApplicationKey.CREATOR.createFromParcel(mParcel);
        assertArrayEquals(KEY_MATERIAL, readFromParcel.getEncryptedKeyMaterial());
    }

    @Test
    public void writeToParcel_writesMetadata_nonNull() {
        buildTestKeyWithMetadata(METADATA).writeToParcel(mParcel, /*flags=*/ 0);

        mParcel.setDataPosition(0);
        WrappedApplicationKey readFromParcel =
                WrappedApplicationKey.CREATOR.createFromParcel(mParcel);
        assertArrayEquals(METADATA, readFromParcel.getMetadata());
    }

    @Test
    public void writeToParcel_writesMetadata_null() {
        buildTestKeyWithMetadata(/*metadata=*/ null).writeToParcel(mParcel, /*flags=*/ 0);

        mParcel.setDataPosition(0);
        WrappedApplicationKey readFromParcel =
                WrappedApplicationKey.CREATOR.createFromParcel(mParcel);
        assertArrayEquals(null, readFromParcel.getMetadata());
    }

    @Test
    public void writeToParcel_writesMetadata_absent() {
        buildTestKey().writeToParcel(mParcel, /*flags=*/ 0);

        mParcel.setDataPosition(0);
        WrappedApplicationKey readFromParcel =
                WrappedApplicationKey.CREATOR.createFromParcel(mParcel);
        assertArrayEquals(null, readFromParcel.getMetadata());
    }

    private WrappedApplicationKey buildTestKey() {
        return new WrappedApplicationKey.Builder()
                .setAlias(ALIAS)
                .setEncryptedKeyMaterial(KEY_MATERIAL)
                .build();
    }

    private WrappedApplicationKey buildTestKeyWithMetadata(byte[] metadata) {
        return new WrappedApplicationKey.Builder()
                .setAlias(ALIAS)
                .setEncryptedKeyMaterial(KEY_MATERIAL)
                .setMetadata(metadata)
                .build();
    }
}
