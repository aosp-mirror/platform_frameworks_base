/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import android.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public final class PhysicalChannelConfig implements Parcelable {

    // TODO(b/72993578) consolidate these enums in a central location.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CONNECTION_PRIMARY_SERVING, CONNECTION_SECONDARY_SERVING, CONNECTION_UNKNOWN})
    public @interface ConnectionStatus {}

    /**
     * UE has connection to cell for signalling and possibly data (3GPP 36.331, 25.331).
     */
    public static final int CONNECTION_PRIMARY_SERVING = 1;

    /**
     * UE has connection to cell for data (3GPP 36.331, 25.331).
     */
    public static final int CONNECTION_SECONDARY_SERVING = 2;

    /** Connection status is unknown. */
    public static final int CONNECTION_UNKNOWN = Integer.MAX_VALUE;

    /**
     * Connection status of the cell.
     *
     * <p>One of {@link #CONNECTION_PRIMARY_SERVING}, {@link #CONNECTION_SECONDARY_SERVING}.
     */
    private int mCellConnectionStatus;

    /**
     * Cell bandwidth, in kHz.
     */
    private int mCellBandwidthDownlinkKhz;

    public PhysicalChannelConfig(int status, int bandwidth) {
        mCellConnectionStatus = status;
        mCellBandwidthDownlinkKhz = bandwidth;
    }

    public PhysicalChannelConfig(Parcel in) {
        mCellConnectionStatus = in.readInt();
        mCellBandwidthDownlinkKhz = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCellConnectionStatus);
        dest.writeInt(mCellBandwidthDownlinkKhz);
    }

    /**
     * @return Cell bandwidth, in kHz
     */
    public int getCellBandwidthDownlink() {
        return mCellBandwidthDownlinkKhz;
    }

    /**
     * Gets the connection status of the cell.
     *
     * @see #CONNECTION_PRIMARY_SERVING
     * @see #CONNECTION_SECONDARY_SERVING
     * @see #CONNECTION_UNKNOWN
     *
     * @return Connection status of the cell
     */
    @ConnectionStatus
    public int getConnectionStatus() {
        return mCellConnectionStatus;
    }

    /** @return String representation of the connection status */
    private String getConnectionStatusString() {
        switch(mCellConnectionStatus) {
            case CONNECTION_PRIMARY_SERVING:
                return "PrimaryServing";
            case CONNECTION_SECONDARY_SERVING:
                return "SecondaryServing";
            case CONNECTION_UNKNOWN:
                return "Unknown";
            default:
                return "Invalid(" + mCellConnectionStatus + ")";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PhysicalChannelConfig)) {
            return false;
        }

        PhysicalChannelConfig config = (PhysicalChannelConfig) o;
        return mCellConnectionStatus == config.mCellConnectionStatus
                && mCellBandwidthDownlinkKhz == config.mCellBandwidthDownlinkKhz;
    }

    @Override
    public int hashCode() {
        return (mCellBandwidthDownlinkKhz * 29) + (mCellConnectionStatus * 31);
    }

    public static final Parcelable.Creator<PhysicalChannelConfig> CREATOR =
        new Parcelable.Creator<PhysicalChannelConfig>() {
            public PhysicalChannelConfig createFromParcel(Parcel in) {
                return new PhysicalChannelConfig(in);
            }

            public PhysicalChannelConfig[] newArray(int size) {
                return new PhysicalChannelConfig[size];
            }
        };

    @Override
    public String toString() {
        return new StringBuilder()
            .append("{mConnectionStatus=")
            .append(getConnectionStatusString())
            .append(",mCellBandwidthDownlinkKhz=")
            .append(mCellBandwidthDownlinkKhz)
            .append("}")
            .toString();
    }
}
