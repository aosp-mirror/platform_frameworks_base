/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.Parcel;

import androidx.test.filters.LargeTest;

import java.util.Random;

@LargeTest
public class VerifierDeviceIdentityTest extends android.test.AndroidTestCase {
    private static final long TEST_1 = 0x7A5F00FF5A55AAA5L;

    private static final String TEST_1_ENCODED = "HUXY-A75N-FLKV-F";

    private static final String TEST_1_ENCODED_LOWERCASE = "huxy-a75n-flkv-f";

    private static final long TEST_2 = 0x5A05FF5A05F0A555L;

    private static final long TEST_MAXVALUE = Long.MAX_VALUE;

    private static final String TEST_MAXVALUE_ENCODED = "H777-7777-7777-7";

    private static final long TEST_MINVALUE = Long.MIN_VALUE;

    private static final String TEST_MINVALUE_ENCODED = "IAAA-AAAA-AAAA-A";

    private static final long TEST_ZERO = 0L;

    private static final String TEST_ZERO_ENCODED = "AAAA-AAAA-AAAA-A";

    private static final long TEST_NEGONE = -1L;

    private static final String TEST_NEGONE_ENCODED = "P777-7777-7777-7";

    private static final String TEST_OVERFLOW_ENCODED = "QAAA-AAAA-AAAA-A";

    private static final String TEST_SUBSTITUTION_CORRECTED = "OIIO-IIOO-IOOI-I";

    private static final String TEST_SUBSTITUTION_UNCORRECTED = "0110-1100-1001-1";

    public void testVerifierDeviceIdentity_Equals_Success() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);
        VerifierDeviceIdentity id2 = new VerifierDeviceIdentity(TEST_1);

        assertTrue("The two VerifierDeviceIdentity instances should be equal", id1.equals(id2));
    }

    public void testVerifierDeviceIdentity_Equals_Failure() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);
        VerifierDeviceIdentity id2 = new VerifierDeviceIdentity(TEST_2);

        assertFalse("The two VerifierDeviceIdentity instances should be unique", id1.equals(id2));
    }

    public void testVerifierDeviceIdentity_HashCode() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);

        assertEquals("The VerifierDeviceIdentity should have the same hashcode as its identity",
                (int) TEST_1, id1.hashCode());
    }

    public void testVerifierDeviceIdentity_ToString_Success() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);

        assertEquals("The identity should encode correctly to the expected Base 32 string",
                TEST_1_ENCODED, id1.toString());
    }

    public void testVerifierDeviceIdentity_ToString_Largest() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_MAXVALUE);

        assertEquals("The identity should encode correctly to the expected Base 32 string",
                TEST_MAXVALUE_ENCODED, id1.toString());
    }

    public void testVerifierDeviceIdentity_ToString_Zero() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_ZERO);

        assertEquals("The identity should encode correctly to the expected Base 32 string",
                TEST_ZERO_ENCODED, id1.toString());
    }

    public void testVerifierDeviceIdentity_ToString_NegOne() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_NEGONE);

        assertEquals("The identity should encode correctly to the expected Base 32 string",
                TEST_NEGONE_ENCODED, id1.toString());
    }

    public void testVerifierDeviceIdentity_ToString_MinValue() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_MINVALUE);

        assertEquals("The identity should encode correctly to the expected Base 32 string",
                TEST_MINVALUE_ENCODED, id1.toString());
    }

    public void testVerifierDeviceIdentity_Parcel_ReadNegative() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_MINVALUE);

        Parcel parcel = Parcel.obtain();
        parcel.writeLong(TEST_MINVALUE);
        parcel.setDataPosition(0);

        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.CREATOR.createFromParcel(parcel);

        assertEquals("Parcel created should match expected value", id1, id2);
    }

    public void testVerifierDeviceIdentity_Parcel_Read_Pass() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);

        Parcel parcel = Parcel.obtain();
        id1.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.CREATOR.createFromParcel(parcel);

        assertEquals("Original identity and parceled identity should be the same", id1, id2);
    }

    @SuppressWarnings("serial")
    private static class MockRandom extends Random {
        private long mNextLong;

        public MockRandom() {
        }

        public void setNextLong(long nextLong) {
            mNextLong = nextLong;
        }

        @Override
        public long nextLong() {
            return mNextLong;
        }
    }

    public void testVerifierDeviceIdentity_Generate_MinValue() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_MINVALUE);

        MockRandom random = new MockRandom();
        random.setNextLong(Long.MIN_VALUE);
        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.generate(random);

        assertEquals("Identity created from Long.MIN_VALUE and one created from return from RNG"
                + " should be the same", id1, id2);
    }

    public void testVerifierDeviceIdentity_Generate_Random() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);

        MockRandom random = new MockRandom();
        random.setNextLong(TEST_1);
        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.generate(random);

        assertEquals("Identity should end up being same when coming from RNG", id1, id2);
    }

    public void testVerifierDeviceIdentity_Parse_Normal() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);

        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.parse(TEST_1_ENCODED);

        assertEquals("Parsed device identity should have the same value as original identity",
                id1, id2);
    }

    public void testVerifierDeviceIdentity_Parse_MaxValue() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_MAXVALUE);

        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.parse(TEST_MAXVALUE_ENCODED);

        assertEquals("Original max value and parsed max value should be equal", id1, id2);
    }

    public void testVerifierDeviceIdentity_Parse_TooShort() {
        try {
            VerifierDeviceIdentity.parse("AAAA-AAAA-AAAA-");
            fail("Parsing should fail when device identifier is too short");
        } catch (IllegalArgumentException e) {
            // success
        }
    }

    public void testVerifierDeviceIdentity_Parse_WayTooShort() {
        try {
            VerifierDeviceIdentity.parse("----------------");
            fail("Parsing should fail when device identifier is too short");
        } catch (IllegalArgumentException e) {
            // success
        }
    }

    public void testVerifierDeviceIdentity_Parse_TooLong() {
        try {
            VerifierDeviceIdentity.parse("AAAA-AAAA-AAAA-AA");
            fail("Parsing should fail when device identifier is too long");
        } catch (IllegalArgumentException e) {
            // success
        }
    }

    public void testVerifierDeviceIdentity_Parse_Overflow() {
        try {
            VerifierDeviceIdentity.parse(TEST_OVERFLOW_ENCODED);
            fail("Parsing should fail when the value will overflow");
        } catch (IllegalArgumentException e) {
            // success
        }
    }

    public void testVerifierDeviceIdentity_Parse_SquashToUppercase() {
        VerifierDeviceIdentity id1 = new VerifierDeviceIdentity(TEST_1);

        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.parse(TEST_1_ENCODED_LOWERCASE);

        assertEquals("Lowercase should parse to be the same as uppercase", id1, id2);

        assertEquals("Substituted identity should render to the same string",
                id1.toString(), id2.toString());
    }

    public void testVerifierDeviceIdentity_Parse_1I_And_0O_Substitution() {
        VerifierDeviceIdentity id1 = VerifierDeviceIdentity.parse(TEST_SUBSTITUTION_CORRECTED);

        VerifierDeviceIdentity id2 = VerifierDeviceIdentity.parse(TEST_SUBSTITUTION_UNCORRECTED);

        assertEquals("Substitution should replace 0 with O and 1 with I", id1, id2);

        assertEquals("Substituted identity should render to the same string",
                id1.toString(), id2.toString());
    }
}
