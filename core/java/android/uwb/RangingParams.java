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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;
import android.util.Duration;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * An object used when requesting to open a new {@link RangingSession}.
 * <p>Use {@link RangingParams.Builder} to create an instance of this class.
 *
 *  @hide
 */
public final class RangingParams {
    /**
     * Standard builder interface as the class is not modifiable
     */
    public static class Builder {
        // TODO implement
    }

    /**
     * Get if the local device is the initiator
     *
     * @return true if the device is the initiator
     */
    public boolean isInitiator() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get if the local device is the controller
     *
     * @return true if the device is the controller
     */
    public boolean isController() {
        throw new UnsupportedOperationException();
    }

    /**
     * The desired amount of time between two adjacent samples of measurement
     *
     * @return the ranging sample period
     */
    @NonNull
    public Duration getSamplingPeriod() {
        throw new UnsupportedOperationException();
    }

    /**
     * Local device's {@link UwbAddress}
     *
     * <p>Simultaneous {@link RangingSession}s on the same device can have different results for
     * {@link #getLocalDeviceAddress()}.
     *
     * @return the local device's {@link UwbAddress}
     */
    @NonNull
    public UwbAddress getLocalDeviceAddress() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets a list of all remote device's {@link UwbAddress}
     *
     * @return a {@link List} of {@link UwbAddress} representing the remote devices
     */
    @NonNull
    public List<UwbAddress> getRemoteDeviceAddresses() {
        throw new UnsupportedOperationException();
    }

    /**
     * Channel number used between this device pair as defined by 802.15.4z
     *
     * Range: -1, 0-15
     *
     * @return the channel to use
     */
    public int getChannelNumber() {
        throw new UnsupportedOperationException();
    }

    /**
     * Preamble index used between this device pair as defined by 802.15.4z
     *
     * Range: 0, 0-32
     *
     * @return the preamble index to use for transmitting
     */
    public int getTxPreambleIndex() {
        throw new UnsupportedOperationException();
    }

    /**
     * preamble index used between this device pair as defined by 802.15.4z
     *
     * Range: 0, 13-16, 21-32
     *
     * @return the preamble index to use for receiving
     */
    public int getRxPreambleIndex() {
        throw new UnsupportedOperationException();
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            STS_PHY_PACKET_TYPE_SP0,
            STS_PHY_PACKET_TYPE_SP1,
            STS_PHY_PACKET_TYPE_SP2,
            STS_PHY_PACKET_TYPE_SP3})
    public @interface StsPhyPacketType {}

    /**
     * PHY packet type SP0 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP0 = 0;

    /**
     * PHY packet type SP1 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP1 = 1;

    /**
     * PHY packet type SP2 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP2 = 2;

    /**
     * PHY packet type SP3 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP3 = 3;

    /**
     * Get the type of PHY packet when STS is used as defined by 802.15.4z
     *
     * @return the {@link StsPhyPacketType} to use
     */
    @StsPhyPacketType
    public int getStsPhyPacketType() {
        throw new UnsupportedOperationException();
    }

    /**
     * Parameters for a specific UWB protocol constructed using a support library.
     *
     * <p>Android reserves the '^android.*' namespace
     *
     * @return a {@link PersistableBundle} of protocol specific parameters
     */
    public @Nullable PersistableBundle getSpecificationParameters() {
        throw new UnsupportedOperationException();
    }
}
