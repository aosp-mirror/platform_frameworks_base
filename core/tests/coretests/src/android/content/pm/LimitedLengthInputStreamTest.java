/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content.pm;

import android.platform.test.annotations.Presubmit;
import android.test.AndroidTestCase;

import androidx.test.filters.MediumTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

@Presubmit
public class LimitedLengthInputStreamTest extends AndroidTestCase {
    private final byte[] TEST_STRING1 = "This is a test".getBytes();

    private InputStream mTestStream1;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mTestStream1 = new ByteArrayInputStream(TEST_STRING1);
    }

    @MediumTest
    public void testConstructor_NegativeOffset_Failure() throws Exception {
        try {
            InputStream is = new LimitedLengthInputStream(mTestStream1, -1, TEST_STRING1.length);
            fail("Should throw IOException on negative index");
        } catch (IOException e) {
            // success
        }
    }

    @MediumTest
    public void testConstructor_NegativeLength_Failure() throws Exception {
        try {
            InputStream is = new LimitedLengthInputStream(mTestStream1, 0, -1);
            fail("Should throw IOException on negative length");
        } catch (IOException e) {
            // success
        }
    }

    @MediumTest
    public void testConstructor_NullInputStream_Failure() throws Exception {
        try {
            InputStream is = new LimitedLengthInputStream(null, 0, 1);
            fail("Should throw IOException on null input stream");
        } catch (IOException e) {
            // success
        }
    }

    @MediumTest
    public void testConstructor_OffsetLengthOverflow_Fail() throws Exception {
        try {
        InputStream is = new LimitedLengthInputStream(mTestStream1, Long.MAX_VALUE - 1,
                Long.MAX_VALUE - 1);
            fail("Should fail when offset + length is > Long.MAX_VALUE");
        } catch (IOException e) {
            // success
        }
    }

    private void checkReadBytesWithOffsetAndLength_WithString1(int offset, int length)
            throws Exception {
        byte[] temp = new byte[TEST_STRING1.length];
        byte[] expected = new byte[length];
        byte[] actual = new byte[length];

        System.arraycopy(TEST_STRING1, offset, expected, 0, length);

        InputStream is = new LimitedLengthInputStream(mTestStream1, offset, length);
        assertEquals(length, is.read(temp, 0, temp.length));

        System.arraycopy(temp, 0, actual, 0, length);
        assertTrue(Arrays.equals(expected, actual));

        assertEquals(-1, is.read(temp, 0, temp.length));
    }

    @MediumTest
    public void testReadBytesWithOffsetAndLength_ZeroOffset_PartialLength_Success()
            throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(0, 2);
    }

    @MediumTest
    public void testReadBytesWithOffsetAndLength_NonZeroOffset_PartialLength_Success()
            throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(3, 2);
    }

    @MediumTest
    public void testReadBytesWithOffsetAndLength_ZeroOffset_FullLength_Success() throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(0, TEST_STRING1.length);
    }

    @MediumTest
    public void testReadBytesWithOffsetAndLength_NonZeroOffset_FullLength_Success()
            throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(3, TEST_STRING1.length - 3);
    }

    @MediumTest
    public void testReadBytesWithOffsetAndLength_ZeroOffset_PastEnd_Success() throws Exception {
        byte[] temp = new byte[TEST_STRING1.length + 10];
        InputStream is = new LimitedLengthInputStream(mTestStream1, 0, TEST_STRING1.length + 10);
        assertEquals(TEST_STRING1.length, is.read(temp, 0, TEST_STRING1.length + 10));

        byte[] actual = new byte[TEST_STRING1.length];
        System.arraycopy(temp, 0, actual, 0, actual.length);
        assertTrue(Arrays.equals(TEST_STRING1, actual));
    }

    private void checkReadBytes_WithString1(int offset, int length) throws Exception {
        byte[] temp = new byte[TEST_STRING1.length];
        byte[] expected = new byte[length];
        byte[] actual = new byte[length];

        System.arraycopy(TEST_STRING1, offset, expected, 0, length);

        InputStream is = new LimitedLengthInputStream(mTestStream1, offset, length);
        assertEquals(length, is.read(temp));

        System.arraycopy(temp, 0, actual, 0, length);
        assertTrue(Arrays.equals(expected, actual));

        assertEquals(-1, is.read(temp));
    }

    @MediumTest
    public void testReadBytes_ZeroOffset_PartialLength_Success() throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(0, 2);
    }

    @MediumTest
    public void testReadBytes_NonZeroOffset_PartialLength_Success() throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(3, 2);
    }

    @MediumTest
    public void testReadBytes_ZeroOffset_FullLength_Success() throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(0, TEST_STRING1.length);
    }

    @MediumTest
    public void testReadBytes_NonZeroOffset_FullLength_Success() throws Exception {
        checkReadBytesWithOffsetAndLength_WithString1(3, TEST_STRING1.length - 3);
    }

    private void checkSingleByteRead_WithString1(int offset, int length) throws Exception {
        InputStream is = new LimitedLengthInputStream(mTestStream1, offset, length);

        for (int i = 0; i < length; i++) {
            assertEquals(TEST_STRING1[offset + i], is.read());
        }

        assertEquals(-1, is.read());
    }

    @MediumTest
    public void testSingleByteRead_ZeroOffset_PartialLength_Success() throws Exception {
        checkSingleByteRead_WithString1(0, 2);
    }

    @MediumTest
    public void testSingleByteRead_NonZeroOffset_PartialLength_Success() throws Exception {
        checkSingleByteRead_WithString1(3, 2);
    }

    @MediumTest
    public void testSingleByteRead_ZeroOffset_FullLength_Success() throws Exception {
        checkSingleByteRead_WithString1(0, TEST_STRING1.length);
    }

    @MediumTest
    public void testSingleByteRead_NonZeroOffset_FullLength_Success() throws Exception {
        checkSingleByteRead_WithString1(3, TEST_STRING1.length - 3);
    }
}
