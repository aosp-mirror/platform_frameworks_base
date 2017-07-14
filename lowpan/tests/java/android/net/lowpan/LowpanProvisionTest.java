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
public class LowpanProvisionTest {

    static {
        System.loadLibrary("frameworkslowpantestsjni");
    }

    private static native byte[] readAndWriteNative(byte[] inParcel);

    public void testNativeParcelUnparcel(LowpanProvision original) {
        byte[] inParcel = marshall(original);
        byte[] outParcel = readAndWriteNative(inParcel);
        LowpanProvision roundTrip = unmarshall(outParcel);

        assertEquals(original, roundTrip);
        assertArrayEquals(inParcel, outParcel);
    }

    @Test
    public void testNativeParcelUnparcel() {
        testNativeParcelUnparcel(
                new LowpanProvision.Builder()
                        .setLowpanIdentity(
                                new LowpanIdentity.Builder()
                                        .setName("TestNet1")
                                        .setPanid(0x1234)
                                        .setXpanid(
                                                new byte[] {
                                                    (byte) 0x00,
                                                    (byte) 0x11,
                                                    (byte) 0x22,
                                                    (byte) 0x33,
                                                    (byte) 0x44,
                                                    (byte) 0x55,
                                                    (byte) 0x66,
                                                    (byte) 0x77
                                                })
                                        .setType(LowpanInterface.NETWORK_TYPE_THREAD_V1)
                                        .setChannel(15)
                                        .build())
                        .build());
        testNativeParcelUnparcel(
                new LowpanProvision.Builder()
                        .setLowpanIdentity(
                                new LowpanIdentity.Builder()
                                        .setName("TestNet2")
                                        .setPanid(0x5678)
                                        .setXpanid(
                                                new byte[] {
                                                    (byte) 0x88,
                                                    (byte) 0x99,
                                                    (byte) 0xaa,
                                                    (byte) 0xbb,
                                                    (byte) 0xcc,
                                                    (byte) 0xdd,
                                                    (byte) 0xee,
                                                    (byte) 0xff
                                                })
                                        .setType("bork-bork-bork")
                                        .setChannel(16)
                                        .build())
                        .setLowpanCredential(
                                LowpanCredential.createMasterKey(
                                        new byte[] {
                                            (byte) 0x88,
                                            (byte) 0x99,
                                            (byte) 0xaa,
                                            (byte) 0xbb,
                                            (byte) 0xcc,
                                            (byte) 0xdd,
                                            (byte) 0xee,
                                            (byte) 0xff
                                        }))
                        .build());
    }

    /**
     * Write a {@link LowpanProvision} into an empty parcel and return the underlying data.
     *
     * @see unmarshall(byte[])
     */
    private static byte[] marshall(LowpanProvision addr) {
        Parcel p = Parcel.obtain();
        addr.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        return p.marshall();
    }

    /**
     * Read raw bytes into a parcel, and read a {@link LowpanProvision} back out of them.
     *
     * @see marshall(LowpanProvision)
     */
    private static LowpanProvision unmarshall(byte[] data) {
        Parcel p = Parcel.obtain();
        p.unmarshall(data, 0, data.length);
        p.setDataPosition(0);
        return LowpanProvision.CREATOR.createFromParcel(p);
    }
}
