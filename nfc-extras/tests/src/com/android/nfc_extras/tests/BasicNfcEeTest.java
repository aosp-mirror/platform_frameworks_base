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

package com.android.nfc_extras.tests;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.test.InstrumentationTestCase;
import android.util.Log;

import com.android.nfc_extras.NfcAdapterExtras;
import com.android.nfc_extras.NfcAdapterExtras.CardEmulationRoute;
import com.android.nfc_extras.NfcExecutionEnvironment;

import java.io.IOException;
import java.util.Arrays;

public class BasicNfcEeTest extends InstrumentationTestCase {
    private Context mContext;
    private NfcAdapterExtras mAdapterExtras;
    private NfcExecutionEnvironment mEe;

    public static final byte[] SELECT_CARD_MANAGER_COMMAND = new byte[] {
        (byte)0x00, (byte)0xA4, (byte)0x04, (byte)0x00,  // command
        (byte)0x08,  // data length
        (byte)0xA0, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x00, (byte)0x00,
        (byte)0x00,  // card manager AID
        (byte)0x00  // trailer
    };

    public static final byte[] SELECT_CARD_MANAGER_RESPONSE = new byte[] {
        (byte)0x90, (byte)0x00,
    };

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getTargetContext();
        mAdapterExtras = NfcAdapterExtras.get(NfcAdapter.getDefaultAdapter(mContext));
        mEe = mAdapterExtras.getEmbeddedExecutionEnvironment();
    }

    public void testSendCardManagerApdu() throws IOException {
        mEe.open();

        try {
            byte[] out = mEe.transceive(SELECT_CARD_MANAGER_COMMAND);
            assertTrue(out.length >= SELECT_CARD_MANAGER_RESPONSE.length);
            byte[] trailing = Arrays.copyOfRange(out,
                    out.length - SELECT_CARD_MANAGER_RESPONSE.length,
                    out.length);
            assertByteArrayEquals(SELECT_CARD_MANAGER_RESPONSE, trailing);

        } finally {
            mEe.close();
        }

    }

    public void testSendCardManagerApduMultiple() throws IOException {
        for (int i=0; i<10; i++) {
            try {
            mEe.open();

            try {
                byte[] out = mEe.transceive(SELECT_CARD_MANAGER_COMMAND);
                byte[] trailing = Arrays.copyOfRange(out,
                        out.length - SELECT_CARD_MANAGER_RESPONSE.length,
                        out.length);

            } finally {
                try {Thread.sleep(1000);} catch (InterruptedException e) {}
                mEe.close();
            }
            } catch (IOException e) {}
        }

        testSendCardManagerApdu();

    }

    public void testEnableEe() {
        mAdapterExtras.setCardEmulationRoute(
                new CardEmulationRoute(CardEmulationRoute.ROUTE_ON_WHEN_SCREEN_ON, mEe));
        CardEmulationRoute newRoute = mAdapterExtras.getCardEmulationRoute();
        assertEquals(CardEmulationRoute.ROUTE_ON_WHEN_SCREEN_ON, newRoute.route);
        assertEquals(mEe, newRoute.nfcEe);
    }

    public void testDisableEe() {
        mAdapterExtras.setCardEmulationRoute(
                new CardEmulationRoute(CardEmulationRoute.ROUTE_OFF, null));
        CardEmulationRoute newRoute = mAdapterExtras.getCardEmulationRoute();
        assertEquals(CardEmulationRoute.ROUTE_OFF, newRoute.route);
        assertNull(newRoute.nfcEe);
    }

    private static void assertByteArrayEquals(byte[] b1, byte[] b2) {
        assertEquals(b1.length, b2.length);
        for (int i = 0; i < b1.length; i++) {
            assertEquals(b1[i], b2[i]);
        }
    }
}
