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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

/**
 * A parcelable representing a nearby device that can be used for media transfer.
 * <p>
 * This class includes:
 * <ul>
 *   <li>an ID identifying the media route.</li>
 *   <li>a range zone specifying how far away this device is from the device with the media route.
 *   </li>
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class NearbyDevice implements Parcelable {
    /**
     * Unknown distance range.
     */
    public static final int RANGE_UNKNOWN = 0;

    /**
     * Distance is very far away from the peer device.
     */
    public static final int RANGE_FAR = 1;

    /**
     * Distance is relatively long from the peer device, typically a few meters.
     */
    public static final int RANGE_LONG = 2;

    /**
     * Distance is close to the peer device, typically with one or two meter.
     */
    public static final int RANGE_CLOSE = 3;

    /**
     * Distance is very close to the peer device, typically within one meter or less.
     */
    public static final int RANGE_WITHIN_REACH = 4;

    /**
     * The various range zones a device can be in, in relation to the current device.
     *
     * @hide
     */
    @IntDef(prefix = {"RANGE_"}, value = {
            RANGE_UNKNOWN,
            RANGE_FAR,
            RANGE_LONG,
            RANGE_CLOSE,
            RANGE_WITHIN_REACH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RangeZone {
    }

    /**
     * Gets a human-readable string of the range zone.
     *
     * @hide
     */
    @NonNull
    public static String rangeZoneToString(@RangeZone int rangeZone) {
        switch (rangeZone) {
            case RANGE_UNKNOWN:
                return "UNKNOWN";
            case RANGE_FAR:
                return "FAR";
            case RANGE_LONG:
                return "LONG";
            case RANGE_CLOSE:
                return "CLOSE";
            case RANGE_WITHIN_REACH:
                return "WITHIN_REACH";
            default:
                return "Invalid";
        }
    }

    /**
     * A list stores all the range and list from far to close, used for range comparison.
     */
    private static final List<Integer> RANGE_WEIGHT_LIST =
            Arrays.asList(RANGE_UNKNOWN,
                    RANGE_FAR, RANGE_LONG, RANGE_CLOSE, RANGE_WITHIN_REACH);

    @NonNull
    private final String mMediaRoute2Id;
    @RangeZone
    private final int mRangeZone;

    /** Creates a device object with the given ID and range zone. */
    public NearbyDevice(@NonNull String mediaRoute2Id, @RangeZone int rangeZone) {
        mMediaRoute2Id = mediaRoute2Id;
        mRangeZone = rangeZone;
    }

    private NearbyDevice(@NonNull Parcel in) {
        mMediaRoute2Id = in.readString8();
        mRangeZone = in.readInt();
    }

    @NonNull
    public static final Creator<NearbyDevice> CREATOR = new Creator<NearbyDevice>() {
        @Override
        public NearbyDevice createFromParcel(@NonNull Parcel in) {
            return new NearbyDevice(in);
        }

        @Override
        public NearbyDevice[] newArray(int size) {
            return new NearbyDevice[size];
        }
    };

    /**
     * Compares two ranges and return result.
     *
     * @return 0 means two ranges are the same, -1 means first range is closer, 1 means farther
     *
     * @hide
     */
    public static int compareRangeZones(@RangeZone int rangeZone, @RangeZone int anotherRangeZone) {
        if (rangeZone == anotherRangeZone) {
            return 0;
        } else {
            return RANGE_WEIGHT_LIST.indexOf(rangeZone) > RANGE_WEIGHT_LIST.indexOf(
                    anotherRangeZone) ? -1 : 1;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "NearbyDevice{mediaRoute2Id=" + mMediaRoute2Id
                + " rangeZone=" + rangeZoneToString(mRangeZone) + "}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mMediaRoute2Id);
        dest.writeInt(mRangeZone);
    }

    /**
     * Returns the ID of the media route associated with the device.
     *
     * @see MediaRoute2Info#getId
     */
    @NonNull
    public String getMediaRoute2Id() {
        return mMediaRoute2Id;
    }

    /** Returns the range that the device is currently in. */
    @RangeZone
    public int getRangeZone() {
        return mRangeZone;
    }
}
