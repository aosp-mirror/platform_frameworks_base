/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test of {@link SessionHandle}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SessionHandleTest {

    @Test
    public void testBasic() {
        int handleId = 12;
        SessionHandle handle = new SessionHandle(handleId);
        assertEquals(handle.getId(), handleId);
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        SessionHandle handle = new SessionHandle(10);
        handle.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SessionHandle fromParcel = SessionHandle.CREATOR.createFromParcel(parcel);
        assertEquals(handle, fromParcel);
    }
}
