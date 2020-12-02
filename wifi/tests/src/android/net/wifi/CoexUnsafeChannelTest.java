/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.net.wifi.WifiScanner.WIFI_BAND_24_GHZ;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.CoexUnsafeChannel}.
 */
@SmallTest
public class CoexUnsafeChannelTest {
    /**
     * Verifies {@link CoexUnsafeChannel#isPowerCapAvailable()} returns false if no cap is set.
     */
    @Test
    public void testIsPowerCapAvailable_noPowerCap_returnsFalse() {
        CoexUnsafeChannel unsafeChannel = new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6);

        assertThat(unsafeChannel.isPowerCapAvailable()).isFalse();
    }

    /**
     * Verifies {@link CoexUnsafeChannel#isPowerCapAvailable()} returns true if a cap is set, and
     * {@link CoexUnsafeChannel#getPowerCapDbm()} returns the set value.
     */
    @Test
    public void testIsPowerCapAvailable_powerCapSet_returnsTrue() {
        final int powerCapDbm = -50;
        CoexUnsafeChannel unsafeChannel = new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6);

        unsafeChannel.setPowerCapDbm(powerCapDbm);

        assertThat(unsafeChannel.isPowerCapAvailable()).isTrue();
        assertThat(unsafeChannel.getPowerCapDbm()).isEqualTo(powerCapDbm);
    }

    /**
     * Verifies {@link CoexUnsafeChannel#getPowerCapDbm()} throws an IllegalStateException if
     * {@link CoexUnsafeChannel#isPowerCapAvailable()} is {@code false}.
     */
    @Test(expected = IllegalStateException.class)
    public void testGetPowerCap_powerCapUnavailable_throwsException() {
        CoexUnsafeChannel unsafeChannel = new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6);

        unsafeChannel.getPowerCapDbm();
    }

    /**
     * Verify parcel read/write for CoexUnsafeChannel with or without power cap.
     */
    @Test
    public void testParcelReadWrite_withOrWithoutCap_readEqualsWritten() throws Exception {
        CoexUnsafeChannel writeUnsafeChannelNoCap =
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6);
        CoexUnsafeChannel writeUnsafeChannelCapped =
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6, -50);

        CoexUnsafeChannel readUnsafeChannelNoCap = parcelReadWrite(writeUnsafeChannelNoCap);
        CoexUnsafeChannel readUnsafeChannelCapped = parcelReadWrite(writeUnsafeChannelCapped);

        assertThat(writeUnsafeChannelNoCap).isEqualTo(readUnsafeChannelNoCap);
        assertThat(writeUnsafeChannelCapped).isEqualTo(readUnsafeChannelCapped);
    }

    /**
     * Write the provided {@link CoexUnsafeChannel} to a parcel and deserialize it.
     */
    private static CoexUnsafeChannel parcelReadWrite(CoexUnsafeChannel writeResult)
            throws Exception {
        Parcel parcel = Parcel.obtain();
        writeResult.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        return CoexUnsafeChannel.CREATOR.createFromParcel(parcel);
    }
}
