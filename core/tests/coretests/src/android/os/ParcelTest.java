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

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class ParcelTest {
    private static final int WORK_SOURCE_1 = 1000;
    private static final int WORK_SOURCE_2 = 1002;
    private static final String INTERFACE_TOKEN_1 = "IBinder interface token";
    private static final String INTERFACE_TOKEN_2 = "Another IBinder interface token";

    @Test
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
}
