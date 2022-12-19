/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware.usb;

import android.Manifest;
import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Holds information related to DisplayPort Alt Mode statuses
 *
 * @hide
 */
@SystemApi
public final class DisplayPortAltModeInfo implements Parcelable {
    private final @DisplayPortAltModeStatus int mPartnerSinkStatus;
    private final @DisplayPortAltModeStatus int mCableStatus;
    private final int mNumLanes;

    /**
     * Port Partners:
     * The port partner status is currently unknown for one of the following reasons:
     *     <ul>
     *     <li> No port partner is connected to the device
     *     <li> The USB Power Delivery Discover Identity command has not been issued to the port
     *     partner via SOP messaging.
     *     </ul>
     * <p>
     * Cables:
     * The cable’s capabilities are not yet known to the device, or no cable is plugged in.
     */
    public static final int DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN = 0;

    /**
     * Port Partners:
     * The current port partner does not list DisplayPort as one of its Alt Modes, or does not list
     * the capability to act as a DisplayPort Source or Sink device, or a compatible configuration
     * could not be established.
     * <p>
     * Cables:
     * The cable/adapter’s capabilities do not list DisplayPort as one of its Alt Modes, or a
     * compatible configuration could not be established.
     */
    public static final int DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE = 1;

    /**
     * Port Partners:
     * The current port partner lists compatible DisplayPort capabilities with the device, however
     * may not yet have entered DisplayPort Alt Mode or has configured its port for data
     * transmission.
     * <p>
     * Cables:
     * The Type-C cable/adapter’s capabilities have been discovered and list DisplayPort Alt Mode
     * as one of its capabilities, however may not yet have entered DisplayPort Alt Mode or has been
     * configured for data transmission.
     */
    public static final int DISPLAYPORT_ALT_MODE_STATUS_CAPABLE = 2;

    /**
     * Port Partners:
     * The port partner and device are both configured for DisplayPort Alt Mode.
     * <p>
     * Cables:
     * The Type-C cable/adapter is configured for DisplayPort Alt Mode.
     */
    public static final int DISPLAYPORT_ALT_MODE_STATUS_ENABLED = 3;

    /** @hide */
    @IntDef(prefix = { "DISPLAYPORT_ALT_MODE_STATUS_" }, value = {
            DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN,
            DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE,
            DISPLAYPORT_ALT_MODE_STATUS_CAPABLE,
            DISPLAYPORT_ALT_MODE_STATUS_ENABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisplayPortAltModeStatus {}

    /** @hide */
    public DisplayPortAltModeInfo() {
        mPartnerSinkStatus = DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN;
        mCableStatus = DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN;
        mNumLanes = 0;
    }

    /** @hide */
    public DisplayPortAltModeInfo(int partnerSinkStatus, int cableStatus,
            int numLanes) {
        mPartnerSinkStatus = partnerSinkStatus;
        mCableStatus = cableStatus;
        mNumLanes = numLanes;
    }

    /**
     * Returns the DisplayPort Alt Mode Status for a port partner acting as a sink.
     *
     * @return {@link #DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN}
     *        or {@link #DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE}
     *        or {@link #DISPLAYPORT_ALT_MODE_STATUS_CAPABLE}
     *        or {@link #DISPLAYPORT_ALT_MODE_STATUS_ENABLED}
     */
    public @DisplayPortAltModeStatus int getPartnerSinkStatus() {
        return mPartnerSinkStatus;
    }

    /**
     * Returns the DisplayPort Alt Mode Status for the attached cable
     *
     * @return {@link #DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN}
     *        or {@link #DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE}
     *        or {@link #DISPLAYPORT_ALT_MODE_STATUS_CAPABLE}
     *        or {@link #DISPLAYPORT_ALT_MODE_STATUS_ENABLED}
     */
    public @DisplayPortAltModeStatus int getCableStatus() {
        return mCableStatus;
    }

    /**
     * Returns the number of lanes used to transmit display data.
     *
     */
    public int getNumberOfLanes() {
        return mNumLanes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPartnerSinkStatus);
        dest.writeInt(mCableStatus);
        dest.writeInt(mNumLanes);
    }

    @NonNull
    @Override
    public String toString() {
        return "DisplayPortAltModeInfo{partnerSink="
                + mPartnerSinkStatus
                + " cable="
                + mCableStatus
                + " numLanes="
                + mNumLanes
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisplayPortAltModeInfo)) {
            return false;
        }
        DisplayPortAltModeInfo other = (DisplayPortAltModeInfo) o;
        return this.mPartnerSinkStatus == other.mPartnerSinkStatus
                && this.mCableStatus == other.mCableStatus
                && this.mNumLanes == other.mNumLanes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPartnerSinkStatus, mCableStatus, mNumLanes);
    }

    public static final @NonNull Parcelable.Creator<DisplayPortAltModeInfo> CREATOR =
            new Parcelable.Creator<DisplayPortAltModeInfo>() {
        @Override
        public DisplayPortAltModeInfo createFromParcel(Parcel in) {
            int partnerSinkStatus = in.readInt();
            int cableStatus = in.readInt();
            int numLanes = in.readInt();
            return new DisplayPortAltModeInfo(partnerSinkStatus, cableStatus, numLanes);
        }

        @Override
        public DisplayPortAltModeInfo[] newArray(int size) {
            return new DisplayPortAltModeInfo[size];
        }
    };
}
