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

package android.telephony.data;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents a S-NSSAI as defined in 3GPP TS 24.501, which represents a network slice.
 *
 * There are 2 main fields that define a slice, SliceServiceType and SliceDifferentiator.
 * SliceServiceType defines the type of service provided by the slice, and SliceDifferentiator is
 * used to differentiate between multiple slices of the same type. If the devices is not on HPLMN,
 * the mappedHplmn versions of these 2 fields indicate the corresponding values in HPLMN.
 *
 * @hide
 */
@SystemApi
public final class NetworkSliceInfo implements Parcelable {
    /**
     * When set on a Slice Differentiator, this value indicates that there is no corresponding
     * Slice.
     */
    public static final int SLICE_DIFFERENTIATOR_NO_SLICE = -1;

    /**
     *  Indicates that the service type is not present.
     */
    public static final int SLICE_SERVICE_TYPE_NONE = 0;

    /**
     *  Slice suitable for the handling of 5G enhanced Mobile Broadband.
     */
    public static final int SLICE_SERVICE_TYPE_EMBB = 1;

    /**
     * Slice suitable for the handling of ultra-reliable low latency communications.
     */
    public static final int SLICE_SERVICE_TYPE_URLLC = 2;

    /**
     * Slice suitable for the handling of massive IoT.
     */
    public static final int SLICE_SERVICE_TYPE_MIOT = 3;

    /**
     * The min acceptable value for a Slice Differentiator
     */
    @SuppressLint("MinMaxConstant")
    public static final int MIN_SLICE_DIFFERENTIATOR = -1;

    /**
     * The max acceptable value for a Slice Differentiator
     */
    @SuppressLint("MinMaxConstant")
    public static final int MAX_SLICE_DIFFERENTIATOR = 0xFFFFFE;

    /** @hide */
    @IntDef(prefix = { "SLICE_SERVICE_TYPE_" }, value = {
            SLICE_SERVICE_TYPE_NONE,
            SLICE_SERVICE_TYPE_EMBB,
            SLICE_SERVICE_TYPE_URLLC,
            SLICE_SERVICE_TYPE_MIOT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SliceServiceType {}


    @SliceServiceType
    private final int mSliceServiceType;
    @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
    private final int mSliceDifferentiator;
    @SliceServiceType
    private final int mMappedHplmnSliceServiceType;
    @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
    private final int mMappedHplmnSliceDifferentiator;

    private NetworkSliceInfo(@SliceServiceType int sliceServiceType,
            int sliceDifferentiator, int mappedHplmnSliceServiceType,
            int mappedHplmnSliceDifferentiator) {
        mSliceServiceType = sliceServiceType;
        mSliceDifferentiator = sliceDifferentiator;
        mMappedHplmnSliceDifferentiator = mappedHplmnSliceDifferentiator;
        mMappedHplmnSliceServiceType = mappedHplmnSliceServiceType;
    }

    /**
     * The type of service provided by the slice.
     * <p/>
     * see: 3GPP TS 24.501 Section 9.11.2.8.
     */
    @SliceServiceType
    public int getSliceServiceType() {
        return mSliceServiceType;
    }

    /**
     * Identifies the slice from others with the same Slice Service Type.
     * <p/>
     * Returns {@link #SLICE_DIFFERENTIATOR_NO_SLICE} if {@link #getSliceServiceType} returns
     * {@link #SLICE_SERVICE_TYPE_NONE}.
     * <p/>
     * see: 3GPP TS 24.501 Section 9.11.2.8.
     */
    @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
    public int getSliceDifferentiator() {
        return mSliceDifferentiator;
    }

    /**
     * Corresponds to a Slice Info (S-NSSAI) of the HPLMN.
     * <p/>
     * see: 3GPP TS 24.501 Section 9.11.2.8.
     */
    @SliceServiceType
    public int getMappedHplmnSliceServiceType() {
        return mMappedHplmnSliceServiceType;
    }

    /**
     * This Slice Differentiator corresponds to a {@link NetworkSliceInfo} (S-NSSAI) of the HPLMN;
     * {@link #getSliceDifferentiator()} is mapped to this value.
     * <p/>
     * Returns {@link #SLICE_DIFFERENTIATOR_NO_SLICE} if either of the following are true:
     * <ul>
     * <li>{@link #getSliceDifferentiator()} returns {@link #SLICE_DIFFERENTIATOR_NO_SLICE}</li>
     * <li>{@link #getMappedHplmnSliceServiceType()} returns {@link #SLICE_SERVICE_TYPE_NONE}</li>
     * </ul>
     * <p/>
     * see: 3GPP TS 24.501 Section 9.11.2.8.
     */
    @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
    public int getMappedHplmnSliceDifferentiator() {
        return mMappedHplmnSliceDifferentiator;
    }

    private NetworkSliceInfo(@NonNull Parcel in) {
        mSliceServiceType = in.readInt();
        mSliceDifferentiator = in.readInt();
        mMappedHplmnSliceServiceType = in.readInt();
        mMappedHplmnSliceDifferentiator = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSliceServiceType);
        dest.writeInt(mSliceDifferentiator);
        dest.writeInt(mMappedHplmnSliceServiceType);
        dest.writeInt(mMappedHplmnSliceDifferentiator);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<NetworkSliceInfo> CREATOR =
            new Parcelable.Creator<NetworkSliceInfo>() {
                @Override
                @NonNull
                public NetworkSliceInfo createFromParcel(@NonNull Parcel source) {
                    return new NetworkSliceInfo(source);
                }

                @Override
                @NonNull
                public NetworkSliceInfo[] newArray(int size) {
                    return new NetworkSliceInfo[size];
                }
            };

    @Override
    public String toString() {
        return "SliceInfo{"
                + "mSliceServiceType=" + sliceServiceTypeToString(mSliceServiceType)
                + ", mSliceDifferentiator=" + mSliceDifferentiator
                + ", mMappedHplmnSliceServiceType="
                + sliceServiceTypeToString(mMappedHplmnSliceServiceType)
                + ", mMappedHplmnSliceDifferentiator=" + mMappedHplmnSliceDifferentiator
                + '}';
    }

    private static String sliceServiceTypeToString(@SliceServiceType int sliceServiceType) {
        switch(sliceServiceType) {
            case SLICE_SERVICE_TYPE_NONE:
                return "NONE";
            case SLICE_SERVICE_TYPE_EMBB:
                return "EMBB";
            case SLICE_SERVICE_TYPE_URLLC:
                return "URLLC";
            case SLICE_SERVICE_TYPE_MIOT:
                return "MIOT";
            default:
                return Integer.toString(sliceServiceType);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NetworkSliceInfo sliceInfo = (NetworkSliceInfo) o;
        return mSliceServiceType == sliceInfo.mSliceServiceType
                && mSliceDifferentiator == sliceInfo.mSliceDifferentiator
                && mMappedHplmnSliceServiceType == sliceInfo.mMappedHplmnSliceServiceType
                && mMappedHplmnSliceDifferentiator == sliceInfo.mMappedHplmnSliceDifferentiator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSliceServiceType, mSliceDifferentiator, mMappedHplmnSliceServiceType,
                mMappedHplmnSliceDifferentiator);
    }

    /**
     * Provides a convenient way to set the fields of a {@link NetworkSliceInfo} when creating a
     * new instance.
     *
     * <p>The example below shows how you might create a new {@code SliceInfo}:
     *
     * <pre><code>
     *
     * SliceInfo response = new SliceInfo.Builder()
     *     .setSliceServiceType(SLICE_SERVICE_TYPE_URLLC)
     *     .build();
     * </code></pre>
     */
    public static final class Builder {
        @SliceServiceType
        private int mSliceServiceType = SLICE_SERVICE_TYPE_NONE;
        @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
        private int mSliceDifferentiator = SLICE_DIFFERENTIATOR_NO_SLICE;
        @SliceServiceType
        private int mMappedHplmnSliceServiceType = SLICE_SERVICE_TYPE_NONE;
        @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
        private int mMappedHplmnSliceDifferentiator = SLICE_DIFFERENTIATOR_NO_SLICE;

        /**
         * Default constructor for Builder.
         */
        public Builder() {
        }

        /**
         * Set the Slice Service Type.
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setSliceServiceType(@SliceServiceType int mSliceServiceType) {
            this.mSliceServiceType = mSliceServiceType;
            return this;
        }

        /**
         * Set the Slice Differentiator.
         * <p/>
         * A value of {@link #SLICE_DIFFERENTIATOR_NO_SLICE} indicates that there is no
         * corresponding Slice.
         *
         * @throws IllegalArgumentException if the parameter is not between
         * {@link #MIN_SLICE_DIFFERENTIATOR} and {@link #MAX_SLICE_DIFFERENTIATOR}.
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setSliceDifferentiator(
                @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
                        int sliceDifferentiator) {
            if (sliceDifferentiator < MIN_SLICE_DIFFERENTIATOR
                    || sliceDifferentiator > MAX_SLICE_DIFFERENTIATOR) {
                throw new IllegalArgumentException("The slice diffentiator value is out of range");
            }
            this.mSliceDifferentiator = sliceDifferentiator;
            return this;
        }

        /**
         * Set the HPLMN Slice Service Type.
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setMappedHplmnSliceServiceType(
                @SliceServiceType int mappedHplmnSliceServiceType) {
            this.mMappedHplmnSliceServiceType = mappedHplmnSliceServiceType;
            return this;
        }

        /**
         * Set the HPLMN Slice Differentiator.
         * <p/>
         * A value of {@link #SLICE_DIFFERENTIATOR_NO_SLICE} indicates that there is no
         * corresponding Slice of the HPLMN.
         *
         * @throws IllegalArgumentException if the parameter is not between
         * {@link #MIN_SLICE_DIFFERENTIATOR} and {@link #MAX_SLICE_DIFFERENTIATOR}.
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setMappedHplmnSliceDifferentiator(
                @IntRange(from = MIN_SLICE_DIFFERENTIATOR, to = MAX_SLICE_DIFFERENTIATOR)
                        int mappedHplmnSliceDifferentiator) {
            if (mappedHplmnSliceDifferentiator < MIN_SLICE_DIFFERENTIATOR
                    || mappedHplmnSliceDifferentiator > MAX_SLICE_DIFFERENTIATOR) {
                throw new IllegalArgumentException("The slice diffentiator value is out of range");
            }
            this.mMappedHplmnSliceDifferentiator = mappedHplmnSliceDifferentiator;
            return this;
        }

        /**
         * Build the {@link NetworkSliceInfo}.
         *
         * @return the {@link NetworkSliceInfo} object.
         */
        @NonNull
        public NetworkSliceInfo build() {
            return new NetworkSliceInfo(this.mSliceServiceType, this.mSliceDifferentiator,
                    this.mMappedHplmnSliceServiceType, this.mMappedHplmnSliceDifferentiator);
        }
    }
}
