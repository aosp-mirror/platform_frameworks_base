/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@IgnoreUnderRavenwood(reason = "JNI")
public class WorkSourceParcelTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    /**
     * END_OF_PARCEL_MARKER is added at the end of Parcel on native or java side on write and
     * then read on java or native side on read. This way we can ensure that no extra data
     * is read from the parcel.
     */
    private static final int END_OF_PARCEL_MARKER = 99;

    private native Parcel nativeObtainWorkSourceParcel(int[] uids, String[] names,
            int parcelEndMarker);

    private native void nativeUnparcelAndVerifyWorkSource(Parcel parcel, int[] uids,
            String[] names, int parcelEndMarker);

    static {
        if (!RavenwoodRule.isUnderRavenwood()) {
            System.loadLibrary("worksourceparceltest_jni");
        }
    }

    /**
     * Confirm that we can pass WorkSource from native to Java.
     */
    @Test
    public void testWorkSourceNativeToJava() {
        final int[] uids1 = {1000};
        final int[] uids2 = {1000, 2000};
        final String[] names1 = {"testWorkSource1"};
        final String[] names2 = {"testWorkSource1", "testWorkSource2"};
        unparcelWorkSourceFromNativeAndVerify(/* uids= */ null , /* names= */ null);
        unparcelWorkSourceFromNativeAndVerify(uids1, /* names= */ null);
        unparcelWorkSourceFromNativeAndVerify(uids2, /* names= */ null);
        unparcelWorkSourceFromNativeAndVerify(/* uids= */ null , names1);
        unparcelWorkSourceFromNativeAndVerify(uids1, names1);
        unparcelWorkSourceFromNativeAndVerify(uids2, names2);
    }

    /**
     * Confirm that we can pass WorkSource from Java to native.
     */
    @Test
    public void testWorkSourceJavaToNative() {
        final int[] uids1 = {1000};
        final int[] uids2 = {1000, 2000};
        final String[] names1 = {"testGetWorkSource1"};
        final String[] names2 = {"testGetWorkSource1", "testGetWorkSource2"};

        parcelWorkSourceToNativeAndVerify(/* uids= */ null , /* names= */ null);
        parcelWorkSourceToNativeAndVerify(uids1, /* names= */ null);
        parcelWorkSourceToNativeAndVerify(uids2, /* names= */ null);
        parcelWorkSourceToNativeAndVerify(uids1, names1);
        parcelWorkSourceToNativeAndVerify(uids2, names2);
    }

    /**
     * Helper function to obtain a WorkSource object as parcel from native, with
     * specified uids and names and verify the WorkSource object created from the parcel.
     */
    private void unparcelWorkSourceFromNativeAndVerify(int[] uids, String[] names) {
        // Obtain WorkSource as parcel from native, with uids and names.
        // END_OF_PARCEL_MARKER is written at the end of parcel
        Parcel wsParcel = nativeObtainWorkSourceParcel(uids, names, END_OF_PARCEL_MARKER);
        // read WorkSource created on native side
        WorkSource ws = WorkSource.CREATOR.createFromParcel(wsParcel);
        // read end marker written on native side
        int endMarker = wsParcel.readInt();

        assertEquals(0, wsParcel.dataAvail()); // we have read everything
        assertEquals(END_OF_PARCEL_MARKER, endMarker); // endMarkers match

        if (uids == null) {
            assertEquals(ws.size(), 0);
        } else {
            assertEquals(uids.length, ws.size());
            for (int i = 0; i < ws.size(); i++) {
                assertEquals(ws.getUid(i), uids[i]);
            }
        }
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                assertEquals(ws.getPackageName(i), names[i]);
            }
        }
    }

    /**
     * Helper function to send a WorkSource as parcel from java to native.
     * Native will verify the WorkSource in native is expected.
     */
    private void parcelWorkSourceToNativeAndVerify(int[] uids, String[] names) {
        WorkSource ws = new WorkSource();
        if (uids != null) {
            if (names == null) {
                for (int uid : uids) {
                    ws.add(uid);
                }
            } else {
                assertEquals(uids.length, names.length);
                for (int i = 0; i < uids.length; i++) {
                    ws.add(uids[i], names[i]);
                }
            }
        }
        Parcel wsParcel = Parcel.obtain();
        // write WorkSource on java side
        ws.writeToParcel(wsParcel, 0 /* flags */);
        // write end marker on java side
        wsParcel.writeInt(END_OF_PARCEL_MARKER);
        wsParcel.setDataPosition(0);
        //Verify parcel and end marker on native side
        nativeUnparcelAndVerifyWorkSource(wsParcel, uids, names, END_OF_PARCEL_MARKER);
    }
}
