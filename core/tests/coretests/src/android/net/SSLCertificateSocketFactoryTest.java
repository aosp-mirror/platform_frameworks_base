/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class SSLCertificateSocketFactoryTest {
    @Test
    public void testStringsToLengthPrefixedBytes() {
        byte[] expected = {
                6, 's', 'p', 'd', 'y', '/', '2',
                8, 'h', 't', 't', 'p', '/', '1', '.', '1',
        };
        assertTrue(Arrays.equals(expected, SSLCertificateSocketFactory.toLengthPrefixedList(
                new byte[] { 's', 'p', 'd', 'y', '/', '2' },
                new byte[] { 'h', 't', 't', 'p', '/', '1', '.', '1' })));
    }

    @Test
    public void testStringsToLengthPrefixedBytesEmptyArray() {
        try {
            SSLCertificateSocketFactory.toLengthPrefixedList();
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testStringsToLengthPrefixedBytesEmptyByteArray() {
        try {
            SSLCertificateSocketFactory.toLengthPrefixedList(new byte[0]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testStringsToLengthPrefixedBytesOversizedInput() {
        try {
            SSLCertificateSocketFactory.toLengthPrefixedList(new byte[256]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }
}
