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
 * limitations under the License
 */

package android.net.wifi;

import static org.junit.Assert.assertEquals;

import android.net.wifi.IconInfo;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.IconInfo}.
 */
@SmallTest
public class IconInfoTest {
    private static final String TEST_FILENAME = "testIcon";
    private static final byte[] TEST_DATA = new byte[] {0x12, 0x23, 0x34, 0x45, 0x56, 0x67};

    /**
     * Verify parcel write and read consistency for the given {@link IconInfo}
     *
     * @param writeIcon the {@link IconInfo} to write and verify
     * @throws Exception
     */
    private static void verifyParcel(IconInfo writeIcon) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeIcon.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        IconInfo readIcon = IconInfo.CREATOR.createFromParcel(parcel);
        assertEquals(writeIcon, readIcon);
    }

    /**
     * Verify parcel serialization for a {@link IconInfo} with null data.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithNullData() throws Exception {
        verifyParcel(new IconInfo(TEST_FILENAME, (byte[]) null));
    }

    /**
     * Verify parcel serialization for a {@link IconInfo} with zero length data.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithZeroLengthData() throws Exception {
        verifyParcel(new IconInfo(TEST_FILENAME, new byte[0]));
    }

    /**
     * Verify parcel serialization for a {@link IconInfo} with non-zero length data.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithNonZeroLengthData() throws Exception {
        verifyParcel(new IconInfo(TEST_FILENAME, TEST_DATA));
    }

    /**
     * Verify parcel serialization for a {@link IconInfo} with a null filename.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithNullFilename() throws Exception {
        verifyParcel(new IconInfo(null, TEST_DATA));
    }

    /**
     * Verify the copy constructor with non-null filename and data.
     *
     * @throws Exception
     */
    @Test
    public void verifyCopyConstructor() throws Exception {
        IconInfo source = new IconInfo(TEST_FILENAME, TEST_DATA);
        assertEquals(source, new IconInfo(source));
    }

    /**
     * Verify the copy constructor with null data.
     *
     * @throws Exception
     */
    @Test
    public void verifyCopyConstructorWithNullData() throws Exception {
        IconInfo source = new IconInfo(TEST_FILENAME, (byte[]) null);
        assertEquals(source, new IconInfo(source));
    }

    /**
     * Verify the copy constructor with null file name.
     *
     * @throws Exception
     */
    @Test
    public void verifyCopyConstructorWithNullFilename() throws Exception {
        IconInfo source = new IconInfo(null, TEST_DATA);
        assertEquals(source, new IconInfo(source));
    }
}
