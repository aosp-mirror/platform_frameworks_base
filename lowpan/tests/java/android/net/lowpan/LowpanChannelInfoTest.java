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
 * limitations under the License.
 */

package android.net.lowpan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import android.os.Parcel;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LowpanChannelInfoTest {

    static {
        System.loadLibrary("frameworkslowpantestsjni");
    }

    private static native byte[] readAndWriteNative(byte[] inParcel);

    public void testNativeParcelUnparcel(LowpanChannelInfo original) {
        byte[] inParcel = marshall(original);
        byte[] outParcel = readAndWriteNative(inParcel);
        LowpanChannelInfo roundTrip = unmarshall(outParcel);

        assertEquals(original, roundTrip);
        assertArrayEquals(inParcel, outParcel);
    }

    @Test
    public void testNativeParcelUnparcel() {
        int i;
        for (i = 1; i < 26; i++) {
            testNativeParcelUnparcel(LowpanChannelInfo.getChannelInfoForIeee802154Page0(i));
        }
    }

    /**
     * Write a {@link LowpanChannelInfo} into an empty parcel and return the underlying data.
     *
     * @see unmarshall(byte[])
     */
    private static byte[] marshall(LowpanChannelInfo addr) {
        Parcel p = Parcel.obtain();
        addr.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        return p.marshall();
    }

    /**
     * Read raw bytes into a parcel, and read a {@link LowpanChannelInfo} back out of them.
     *
     * @see marshall(LowpanChannelInfo)
     */
    private static LowpanChannelInfo unmarshall(byte[] data) {
        Parcel p = Parcel.obtain();
        p.unmarshall(data, 0, data.length);
        p.setDataPosition(0);
        return LowpanChannelInfo.CREATOR.createFromParcel(p);
    }
}
