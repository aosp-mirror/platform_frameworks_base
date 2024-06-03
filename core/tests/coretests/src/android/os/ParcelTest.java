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

package android.os;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class ParcelTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private static final int WORK_SOURCE_1 = 1000;
    private static final int WORK_SOURCE_2 = 1002;
    private static final String INTERFACE_TOKEN_1 = "IBinder interface token";
    private static final String INTERFACE_TOKEN_2 = "Another IBinder interface token";

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testIsForRpc() {
        Parcel p = Parcel.obtain();
        assertEquals(false, p.isForRpc());
        p.recycle();
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testCallingWorkSourceUidAfterWrite() {
        Parcel p = Parcel.obtain();
        // Method does not throw if replaceCallingWorkSourceUid is called before requests headers
        // are added.
        assertEquals(false, p.replaceCallingWorkSourceUid(WORK_SOURCE_1));
        assertEquals(Binder.UNSET_WORKSOURCE, p.readCallingWorkSourceUid());

        // WorkSource can be updated.
        p.writeInterfaceToken(INTERFACE_TOKEN_1);
        assertEquals(true, p.replaceCallingWorkSourceUid(WORK_SOURCE_2));
        assertEquals(WORK_SOURCE_2, p.readCallingWorkSourceUid());

        // WorkSource can be updated to unset value.
        assertEquals(true, p.replaceCallingWorkSourceUid(Binder.UNSET_WORKSOURCE));
        assertEquals(Binder.UNSET_WORKSOURCE, p.readCallingWorkSourceUid());

        p.recycle();
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testCallingWorkSourceUidAfterEnforce() {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(INTERFACE_TOKEN_1);
        assertEquals(true, p.replaceCallingWorkSourceUid(WORK_SOURCE_1));
        p.setDataPosition(0);

        p.enforceInterface(INTERFACE_TOKEN_1);
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());

        // WorkSource can be updated.
        assertEquals(true, p.replaceCallingWorkSourceUid(WORK_SOURCE_2));
        assertEquals(WORK_SOURCE_2, p.readCallingWorkSourceUid());

        p.recycle();
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testParcelWithMultipleHeaders() {
        Parcel p = Parcel.obtain();
        Binder.setCallingWorkSourceUid(WORK_SOURCE_1);
        p.writeInterfaceToken(INTERFACE_TOKEN_1);
        Binder.setCallingWorkSourceUid(WORK_SOURCE_2);
        p.writeInterfaceToken(INTERFACE_TOKEN_2);
        p.setDataPosition(0);

        // WorkSource is from the first header.
        p.enforceInterface(INTERFACE_TOKEN_1);
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());
        p.enforceInterface(INTERFACE_TOKEN_2);
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());

        p.recycle();
    }

    /**
     * Verify that writing/reading UTF-8 and UTF-16 strings works well.
     */
    @Test
    public void testStrings() {
        final String[] strings = {
                null, "", "abc\0def", "com.example.typical_package_name",
                "從不喜歡孤單一個 - 蘇永康／吳雨霏", "example"
        };

        final Parcel p = Parcel.obtain();
        for (String string : strings) {
            p.writeString8(string);
            p.writeString16(string);
        }

        p.setDataPosition(0);
        for (String string : strings) {
            assertEquals(string, p.readString8());
            assertEquals(string, p.readString16());
        }
    }

    @Test
    public void testCompareDataInRange_whenSameData() {
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(13);
        pA.writeString("Tiramisu");
        int length = pA.dataPosition() - iA;
        Parcel pB = Parcel.obtain();
        pB.writeString("Prefix");
        int iB = pB.dataPosition();
        pB.writeInt(13);
        pB.writeString("Tiramisu");

        assertTrue(Parcel.compareData(pA, iA, pB, iB, length));
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testCompareDataInRange_whenSameDataWithBinder() {
        Binder binder = new Binder();
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(13);
        pA.writeStrongBinder(binder);
        pA.writeString("Tiramisu");
        int length = pA.dataPosition() - iA;
        Parcel pB = Parcel.obtain();
        pB.writeString("Prefix");
        int iB = pB.dataPosition();
        pB.writeInt(13);
        pB.writeStrongBinder(binder);
        pB.writeString("Tiramisu");

        assertTrue(Parcel.compareData(pA, iA, pB, iB, length));
    }

    @Test
    public void testCompareDataInRange_whenDifferentData() {
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(13);
        pA.writeString("Tiramisu");
        int length = pA.dataPosition() - iA;
        Parcel pB = Parcel.obtain();
        int iB = pB.dataPosition();
        pB.writeString("Prefix");
        pB.writeInt(13);
        pB.writeString("Tiramisu");

        assertFalse(Parcel.compareData(pA, iA, pB, iB, length));
    }

    @Test
    public void testCompareDataInRange_whenLimitOutOfBounds_throws() {
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(12);
        pA.writeString("Tiramisu");
        int length = pA.dataPosition() - iA;
        Parcel pB = Parcel.obtain();
        pB.writeString("Prefix");
        int iB = pB.dataPosition();
        pB.writeInt(13);
        pB.writeString("Tiramisu");
        pB.writeInt(-1);

        assertThrows(IllegalArgumentException.class,
                () -> Parcel.compareData(pA, iA + length, pB, iB, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Parcel.compareData(pA, iA, pB, pB.dataSize(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> Parcel.compareData(pA, iA, pB, iB, length + 1));
        assertThrows(IllegalArgumentException.class,
                () -> Parcel.compareData(pA, iA + length + 1, pB, iB, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Parcel.compareData(pA, iA, pB, iB + pB.dataSize() + 1, 0));
    }

    @Test
    public void testCompareDataInRange_whenLengthZero() {
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(12);
        pA.writeString("Tiramisu");
        int length = pA.dataPosition() - iA;
        Parcel pB = Parcel.obtain();
        pB.writeString("Prefix");
        int iB = pB.dataPosition();
        pB.writeInt(13);
        pB.writeString("Tiramisu");

        assertTrue(Parcel.compareData(pA, 0, pB, iB, 0));
        assertTrue(Parcel.compareData(pA, iA + length, pB, iB, 0));
        assertTrue(Parcel.compareData(pA, iA, pB, pB.dataSize(), 0));
    }

    @Test
    public void testCompareDataInRange_whenNegativeLength_throws() {
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(12);
        pA.writeString("Tiramisu");
        Parcel pB = Parcel.obtain();
        pB.writeString("Prefix");
        int iB = pB.dataPosition();
        pB.writeInt(13);
        pB.writeString("Tiramisu");

        assertThrows(IllegalArgumentException.class, () -> Parcel.compareData(pA, iA, pB, iB, -1));
    }

    @Test
    public void testCompareDataInRange_whenNegativeOffset_throws() {
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(12);
        pA.writeString("Tiramisu");
        Parcel pB = Parcel.obtain();
        pB.writeString("Prefix");
        int iB = pB.dataPosition();
        pB.writeInt(13);
        pB.writeString("Tiramisu");

        assertThrows(IllegalArgumentException.class, () -> Parcel.compareData(pA, -1, pB, iB, 0));
        assertThrows(IllegalArgumentException.class, () -> Parcel.compareData(pA, 0, pB, -1, 0));
    }

    /***
     * Tests for b/205282403
     * This test checks if allocations made over limit of 1MB for primitive types
     * and 1M length for complex objects are not allowed.
     */
    @Test
    public void testAllocationsOverLimit_whenOverLimit_throws() {
        Binder.setIsDirectlyHandlingTransactionOverride(true);
        Parcel p = Parcel.obtain();
        p.setDataPosition(0);
        p.writeInt(Integer.MAX_VALUE);

        p.setDataPosition(0);
        assertThrows(BadParcelableException.class, () ->p.createBooleanArray());

        p.setDataPosition(0);
        assertThrows(BadParcelableException.class, () ->p.createCharArray());

        p.setDataPosition(0);
        assertThrows(BadParcelableException.class, () ->p.createIntArray());

        p.setDataPosition(0);
        assertThrows(BadParcelableException.class, () ->p.createLongArray());

        p.setDataPosition(0);
        assertThrows(BadParcelableException.class, () ->p.createBinderArray());

        int[] dimensions = new int[]{Integer.MAX_VALUE, 100, 100};
        p.setDataPosition(0);
        assertThrows(BadParcelableException.class,
                () -> p.createFixedArray(int[][][].class, dimensions));

        p.setDataPosition(0);
        assertThrows(BadParcelableException.class,
                () -> p.createFixedArray(String[][][].class, dimensions));

        p.setDataPosition(0);
        assertThrows(BadParcelableException.class,
                () -> p.createFixedArray(IBinder[][][].class, dimensions));

        p.recycle();
        Binder.setIsDirectlyHandlingTransactionOverride(false);
    }

    /***
     * Tests for b/205282403
     * This test checks if allocations made under limit of 1MB for primitive types
     * and 1M length for complex objects are allowed.
     */
    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testAllocations_whenWithinLimit() {
        Binder.setIsDirectlyHandlingTransactionOverride(true);
        Parcel p = Parcel.obtain();
        p.setDataPosition(0);
        p.writeInt(100000);

        p.setDataPosition(0);
        p.createByteArray();

        p.setDataPosition(0);
        p.createCharArray();

        p.setDataPosition(0);
        p.createIntArray();

        p.setDataPosition(0);
        p.createLongArray();

        p.setDataPosition(0);
        p.createBinderArray();

        int[] dimensions = new int[]{ 100, 100, 100 };

        p.setDataPosition(0);
        int[][][] data  =  new int[100][100][100];
        p.writeFixedArray(data, 0, dimensions);
        p.setDataPosition(0);
        p.createFixedArray(int[][][].class, dimensions);

        p.setDataPosition(0);
        IBinder[][][] parcelables  =  new IBinder[100][100][100];
        p.writeFixedArray(parcelables, 0, dimensions);
        p.setDataPosition(0);
        p.createFixedArray(IBinder[][][].class, dimensions);

        p.recycle();
        Binder.setIsDirectlyHandlingTransactionOverride(false);
    }

    @Test
    public void testClassCookies() {
        Parcel p = Parcel.obtain();
        assertThat(p.hasClassCookie(ParcelTest.class)).isFalse();

        p.setClassCookie(ParcelTest.class, "string_cookie");
        assertThat(p.hasClassCookie(ParcelTest.class)).isTrue();
        assertThat(p.getClassCookie(ParcelTest.class)).isEqualTo("string_cookie");

        p.removeClassCookie(ParcelTest.class, "string_cookie");
        assertThat(p.hasClassCookie(ParcelTest.class)).isFalse();
        assertThat(p.getClassCookie(ParcelTest.class)).isEqualTo(null);

        p.setClassCookie(ParcelTest.class, "to_be_discarded_cookie");
        p.recycle();
        assertThat(p.getClassCookie(ParcelTest.class)).isNull();
    }

    @Test
    public void testClassCookies_removeUnexpected() {
        Parcel p = Parcel.obtain();

        assertLogsWtf(() -> p.removeClassCookie(ParcelTest.class, "not_present"));

        p.setClassCookie(ParcelTest.class, "value");

        assertLogsWtf(() -> p.removeClassCookie(ParcelTest.class, "different"));
        assertThat(p.getClassCookie(ParcelTest.class)).isNull(); // still removed

        p.recycle();
    }

    private static void assertLogsWtf(Runnable test) {
        ArrayList<Log.TerribleFailure> wtfs = new ArrayList<>();
        Log.TerribleFailureHandler oldHandler = Log.setWtfHandler(
                (tag, what, system) -> wtfs.add(what));
        try {
            test.run();
        } finally {
            Log.setWtfHandler(oldHandler);
        }
        assertThat(wtfs).hasSize(1);
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testHasBinders_AfterWritingBinderToParcel() {
        Binder binder = new Binder();
        Parcel pA = Parcel.obtain();
        int iA = pA.dataPosition();
        pA.writeInt(13);
        assertFalse(pA.hasBinders());
        pA.writeStrongBinder(binder);
        assertTrue(pA.hasBinders());
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void testHasBindersInRange_AfterWritingBinderToParcel() {
        Binder binder = new Binder();
        Parcel pA = Parcel.obtain();
        pA.writeInt(13);

        int binderStartPos = pA.dataPosition();
        pA.writeStrongBinder(binder);
        int binderEndPos = pA.dataPosition();
        assertTrue(pA.hasBinders(binderStartPos, binderEndPos - binderStartPos));
    }
}
