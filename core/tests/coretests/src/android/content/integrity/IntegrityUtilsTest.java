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

package android.content.integrity;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;
import static org.testng.internal.junit.ArrayAsserts.assertArrayEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit test for {@link IntegrityUtils} */
@RunWith(JUnit4.class)
public class IntegrityUtilsTest {

    private static final String HEX_DIGEST = "1234567890ABCDEF";
    private static final byte[] BYTES =
            new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};

    @Test
    public void testGetBytesFromHexDigest() {
        assertArrayEquals(BYTES, IntegrityUtils.getBytesFromHexDigest(HEX_DIGEST));
    }

    @Test
    public void testGetHexDigest() {
        assertThat(IntegrityUtils.getHexDigest(BYTES)).isEqualTo(HEX_DIGEST);
    }

    @Test
    public void testInvalidHexDigest_mustHaveEvenLength() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () -> IntegrityUtils.getBytesFromHexDigest("ABC"));
        assertThat(e.getMessage()).containsMatch("must have even length");
    }

    @Test
    public void testInvalidHexDigest_invalidHexChar() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () -> IntegrityUtils.getBytesFromHexDigest("GH"));
        assertThat(e.getMessage()).containsMatch("Invalid hex char");
    }
}
