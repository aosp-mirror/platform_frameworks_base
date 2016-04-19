/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

import static org.junit.Assert.assertArrayEquals;

public class UidRangeTest extends TestCase {

    static {
        System.loadLibrary("servicestestsjni");
    }

    private static native byte[] readAndWriteNative(byte[] inParcel);
    private static native int getStart(byte[] inParcel);
    private static native int getStop(byte[] inParcel);

    @SmallTest
    public void testNativeParcelUnparcel() {
        UidRange original = new UidRange(1234, Integer.MAX_VALUE);

        byte[] inParcel = marshall(original);
        byte[] outParcel = readAndWriteNative(inParcel);
        UidRange roundTrip = unmarshall(outParcel);

        assertEquals(original, roundTrip);
        assertArrayEquals(inParcel, outParcel);
    }

    @SmallTest
    public void testIndividualNativeFields() {
        UidRange original = new UidRange(0x11115678, 0x22224321);
        byte[] originalBytes = marshall(original);

        assertEquals(original.start, getStart(originalBytes));
        assertEquals(original.stop, getStop(originalBytes));
    }

    @SmallTest
    public void testSingleItemUidRangeAllowed() {
        new UidRange(123, 123);
        new UidRange(0, 0);
        new UidRange(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @SmallTest
    public void testNegativeUidsDisallowed() {
        try {
            new UidRange(-2, 100);
            fail("Exception not thrown for negative start UID");
        } catch (IllegalArgumentException expected) {
        }

        try {
            new UidRange(-200, -100);
            fail("Exception not thrown for negative stop UID");
        } catch (IllegalArgumentException expected) {
        }
    }

    @SmallTest
    public void testStopLessThanStartDisallowed() {
        final int x = 4195000;
        try {
            new UidRange(x, x - 1);
            fail("Exception not thrown for negative-length UID range");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Write a {@link UidRange} into an empty parcel and return the underlying data.
     *
     * @see unmarshall(byte[])
     */
    private static byte[] marshall(UidRange range) {
        Parcel p = Parcel.obtain();
        range.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        return p.marshall();
    }

    /**
     * Read raw bytes into a parcel, and read a {@link UidRange} back out of them.
     *
     * @see marshall(UidRange)
     */
    private static UidRange unmarshall(byte[] data) {
        Parcel p = Parcel.obtain();
        p.unmarshall(data, 0, data.length);
        p.setDataPosition(0);
        return UidRange.CREATOR.createFromParcel(p);
    }
}
