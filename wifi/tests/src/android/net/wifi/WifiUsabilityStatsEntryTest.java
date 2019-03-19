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

package android.net.wifi;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.validateMockitoUsage;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;


/**
 * Unit tests for {@link android.net.wifi.WifiUsabilityStatsEntry}.
 */
@SmallTest
public class WifiUsabilityStatsEntryTest {

    /**
     * Setup before tests.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Clean up after tests.
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Verify parcel read/write for Wifi usability stats result.
     */
    @Test
    public void verifyStatsResultWriteAndThenRead() throws Exception {
        WifiUsabilityStatsEntry writeResult = createResult();
        WifiUsabilityStatsEntry readResult = parcelWriteRead(writeResult);
        assertWifiUsabilityStatsEntryEquals(writeResult, readResult);
    }

    /**
     * Write the provided {@link WifiUsabilityStatsEntry} to a parcel and deserialize it.
     */
    private static WifiUsabilityStatsEntry parcelWriteRead(
            WifiUsabilityStatsEntry writeResult) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeResult.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        return WifiUsabilityStatsEntry.CREATOR.createFromParcel(parcel);
    }

    private static WifiUsabilityStatsEntry createResult() {
        return new WifiUsabilityStatsEntry(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, true
        );
    }

    private static void assertWifiUsabilityStatsEntryEquals(
            WifiUsabilityStatsEntry expected,
            WifiUsabilityStatsEntry actual) {
        assertEquals(expected.getTimeStampMillis(), actual.getTimeStampMillis());
        assertEquals(expected.getRssi(), actual.getRssi());
        assertEquals(expected.getLinkSpeedMbps(), actual.getLinkSpeedMbps());
        assertEquals(expected.getTotalTxSuccess(), actual.getTotalTxSuccess());
        assertEquals(expected.getTotalTxRetries(), actual.getTotalTxRetries());
        assertEquals(expected.getTotalTxBad(), actual.getTotalTxBad());
        assertEquals(expected.getTotalRxSuccess(), actual.getTotalRxSuccess());
        assertEquals(expected.getTotalRadioOnTimeMillis(), actual.getTotalRadioOnTimeMillis());
        assertEquals(expected.getTotalRadioTxTimeMillis(), actual.getTotalRadioTxTimeMillis());
        assertEquals(expected.getTotalRadioRxTimeMillis(), actual.getTotalRadioRxTimeMillis());
        assertEquals(expected.getTotalScanTimeMillis(), actual.getTotalScanTimeMillis());
        assertEquals(expected.getTotalNanScanTimeMillis(), actual.getTotalNanScanTimeMillis());
        assertEquals(expected.getTotalBackgroundScanTimeMillis(),
                actual.getTotalBackgroundScanTimeMillis());
        assertEquals(expected.getTotalRoamScanTimeMillis(), actual.getTotalRoamScanTimeMillis());
        assertEquals(expected.getTotalPnoScanTimeMillis(), actual.getTotalPnoScanTimeMillis());
        assertEquals(expected.getTotalHotspot2ScanTimeMillis(),
                actual.getTotalHotspot2ScanTimeMillis());
        assertEquals(expected.getTotalCcaBusyFreqTimeMillis(),
                actual.getTotalCcaBusyFreqTimeMillis());
        assertEquals(expected.getTotalRadioOnFreqTimeMillis(),
                actual.getTotalRadioOnFreqTimeMillis());
        assertEquals(expected.getTotalBeaconRx(), actual.getTotalBeaconRx());
        assertEquals(expected.getProbeStatusSinceLastUpdate(),
                actual.getProbeStatusSinceLastUpdate());
        assertEquals(expected.getProbeElapsedTimeSinceLastUpdateMillis(),
                actual.getProbeElapsedTimeSinceLastUpdateMillis());
        assertEquals(expected.getProbeMcsRateSinceLastUpdate(),
                actual.getProbeMcsRateSinceLastUpdate());
        assertEquals(expected.getRxLinkSpeedMbps(), actual.getRxLinkSpeedMbps());
        assertEquals(expected.getCellularDataNetworkType(), actual.getCellularDataNetworkType());
        assertEquals(expected.getCellularSignalStrengthDbm(),
                actual.getCellularSignalStrengthDbm());
        assertEquals(expected.getCellularSignalStrengthDb(), actual.getCellularSignalStrengthDb());
        assertEquals(expected.getIsSameRegisteredCell(), actual.getIsSameRegisteredCell());
    }
}
