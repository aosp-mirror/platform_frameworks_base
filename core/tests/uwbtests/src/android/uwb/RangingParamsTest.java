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
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.os.PersistableBundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;

/**
 * Test of {@link RangingParams}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RangingParamsTest {

    @Test
    public void testParams_Build() {
        UwbAddress local = UwbAddress.fromBytes(new byte[] {(byte) 0xA0, (byte) 0x57});
        UwbAddress remote = UwbAddress.fromBytes(new byte[] {(byte) 0x4D, (byte) 0x8C});
        int channel = 9;
        int rxPreamble = 16;
        int txPreamble = 21;
        boolean isController = true;
        boolean isInitiator = false;
        @RangingParams.StsPhyPacketType int stsPhyType = RangingParams.STS_PHY_PACKET_TYPE_SP2;
        Duration samplePeriod = Duration.ofSeconds(1, 234);
        PersistableBundle specParams = new PersistableBundle();
        specParams.putString("protocol", "some_protocol");

        RangingParams params = new RangingParams.Builder()
                .setChannelNumber(channel)
                .setReceivePreambleCodeIndex(rxPreamble)
                .setTransmitPreambleCodeIndex(txPreamble)
                .setLocalDeviceAddress(local)
                .addRemoteDeviceAddress(remote)
                .setIsController(isController)
                .setIsInitiator(isInitiator)
                .setSamplePeriod(samplePeriod)
                .setStsPhPacketType(stsPhyType)
                .setSpecificationParameters(specParams)
                .build();

        assertEquals(params.getLocalDeviceAddress(), local);
        assertEquals(params.getRemoteDeviceAddresses().size(), 1);
        assertEquals(params.getRemoteDeviceAddresses().get(0), remote);
        assertEquals(params.getChannelNumber(), channel);
        assertEquals(params.isController(), isController);
        assertEquals(params.isInitiator(), isInitiator);
        assertEquals(params.getRxPreambleIndex(), rxPreamble);
        assertEquals(params.getTxPreambleIndex(), txPreamble);
        assertEquals(params.getStsPhyPacketType(), stsPhyType);
        assertEquals(params.getSamplingPeriod(), samplePeriod);
        assertTrue(params.getSpecificationParameters().kindofEquals(specParams));
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        RangingParams params = new RangingParams.Builder()
                .setChannelNumber(9)
                .setReceivePreambleCodeIndex(16)
                .setTransmitPreambleCodeIndex(21)
                .setLocalDeviceAddress(UwbTestUtils.getUwbAddress(false))
                .addRemoteDeviceAddress(UwbTestUtils.getUwbAddress(true))
                .setIsController(false)
                .setIsInitiator(true)
                .setSamplePeriod(Duration.ofSeconds(2))
                .setStsPhPacketType(RangingParams.STS_PHY_PACKET_TYPE_SP1)
                .build();
        params.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        RangingParams fromParcel = RangingParams.CREATOR.createFromParcel(parcel);
        Log.w("bstack", "original: " + params.toString());
        Log.w("bstack", "parcel: " + fromParcel.toString());
        assertEquals(params, fromParcel);
    }
}
