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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LowpanIdentityTest {

    static {
        System.loadLibrary("frameworkslowpantestsjni");
    }

    private static native byte[] readAndWriteNative(byte[] inParcel);

    public void testNativeParcelUnparcel(LowpanIdentity original) {
        byte[] inParcel = marshall(original);
        byte[] outParcel = readAndWriteNative(inParcel);
        LowpanIdentity roundTrip = unmarshall(outParcel);

        assertEquals(original, roundTrip);
        assertEquals(original.hashCode(), roundTrip.hashCode());
        assertEquals(original.getName(), roundTrip.getName());
        assertArrayEquals(inParcel, outParcel);
    }

    @Test
    public void testNativeParcelUnparcel1() {
        testNativeParcelUnparcel(
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
                        .build());
    }

    @Test
    public void testNativeParcelUnparcel2() {
        testNativeParcelUnparcel(
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
                        .build());
    }

    @Test
    public void testNativeParcelUnparcel3() {
        testNativeParcelUnparcel(new LowpanIdentity.Builder().setName("TestNet3").build());
    }

    @Test
    public void testNativeParcelUnparcel4() {
        testNativeParcelUnparcel(new LowpanIdentity.Builder().build());
    }

    @Test
    public void testNativeParcelUnparcel5() {
        testNativeParcelUnparcel(
                new LowpanIdentity.Builder()
                        .setRawName(
                                new byte[] {
                                    (byte) 0x66,
                                    (byte) 0x6F,
                                    (byte) 0x6F,
                                    (byte) 0xC2,
                                    (byte) 0xAD,
                                    (byte) 0xCD,
                                    (byte) 0x8F,
                                    (byte) 0xE1,
                                    (byte) 0xA0,
                                    (byte) 0x86,
                                    (byte) 0xE1,
                                    (byte) 0xA0,
                                    (byte) 0x8B
                                })
                        .build());
    }

    @Test
    public void testStringPrep1() {
        LowpanIdentity identity =
                new LowpanIdentity.Builder()
                        .setRawName(
                                new byte[] {
                                    (byte) 0x66,
                                    (byte) 0x6F,
                                    (byte) 0x6F,
                                    (byte) 0x20,
                                    (byte) 0xC2,
                                    (byte) 0xAD,
                                    (byte) 0xCD,
                                    (byte) 0x8F,
                                    (byte) 0xE1,
                                    (byte) 0xA0,
                                    (byte) 0x86,
                                    (byte) 0xE1,
                                    (byte) 0xA0,
                                    (byte) 0x8B
                                })
                        .build();

        assertFalse(identity.isNameValid());
    }

    @Test
    public void testStringPrep2() {
        LowpanIdentity identity =
                new LowpanIdentity.Builder()
                        .setRawName(
                                new byte[] {
                                    (byte) 0x66, (byte) 0x6F, (byte) 0x6F, (byte) 0x20, (byte) 0x6F
                                })
                        .build();

        assertEquals("foo o", identity.getName());
        assertTrue(identity.isNameValid());
    }

    @Test
    public void testStringPrep3() {
        LowpanIdentity identity = new LowpanIdentity.Builder().setName("foo o").build();

        assertTrue(identity.isNameValid());
        assertEquals("foo o", identity.getName());
    }

    /**
     * Write a {@link LowpanIdentity} into an empty parcel and return the underlying data.
     *
     * @see unmarshall(byte[])
     */
    private static byte[] marshall(LowpanIdentity addr) {
        Parcel p = Parcel.obtain();
        addr.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        return p.marshall();
    }

    /**
     * Read raw bytes into a parcel, and read a {@link LowpanIdentity} back out of them.
     *
     * @see marshall(LowpanIdentity)
     */
    private static LowpanIdentity unmarshall(byte[] data) {
        Parcel p = Parcel.obtain();
        p.unmarshall(data, 0, data.length);
        p.setDataPosition(0);
        return LowpanIdentity.CREATOR.createFromParcel(p);
    }
}
