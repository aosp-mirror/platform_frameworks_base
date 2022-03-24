/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.hdmi;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Immutable class that stores support status for features in the [Device Features] operand.
 * Each feature may be supported, be not supported, or have an unknown support status.
 *
 * @hide
 */
public class DeviceFeatures {

    @IntDef({
            FEATURE_NOT_SUPPORTED,
            FEATURE_SUPPORTED,
            FEATURE_SUPPORT_UNKNOWN
    })
    public @interface FeatureSupportStatus {};

    public static final int FEATURE_NOT_SUPPORTED = 0;
    public static final int FEATURE_SUPPORTED = 1;
    public static final int FEATURE_SUPPORT_UNKNOWN = 2;

    /**
     * Instance representing no knowledge of any feature's support.
     */
    @NonNull
    public static final DeviceFeatures ALL_FEATURES_SUPPORT_UNKNOWN =
            new Builder(FEATURE_SUPPORT_UNKNOWN).build();

    /**
     * Instance representing no support for any feature.
     */
    @NonNull
    public static final DeviceFeatures NO_FEATURES_SUPPORTED =
            new Builder(FEATURE_NOT_SUPPORTED).build();

    @FeatureSupportStatus private final int mRecordTvScreenSupport;
    @FeatureSupportStatus private final int mSetOsdStringSupport;
    @FeatureSupportStatus private final int mDeckControlSupport;
    @FeatureSupportStatus private final int mSetAudioRateSupport;
    @FeatureSupportStatus private final int mArcTxSupport;
    @FeatureSupportStatus private final int mArcRxSupport;
    @FeatureSupportStatus private final int mSetAudioVolumeLevelSupport;

    private DeviceFeatures(@NonNull Builder builder) {
        this.mRecordTvScreenSupport = builder.mRecordTvScreenSupport;
        this.mSetOsdStringSupport = builder.mOsdStringSupport;
        this.mDeckControlSupport = builder.mDeckControlSupport;
        this.mSetAudioRateSupport = builder.mSetAudioRateSupport;
        this.mArcTxSupport = builder.mArcTxSupport;
        this.mArcRxSupport = builder.mArcRxSupport;
        this.mSetAudioVolumeLevelSupport = builder.mSetAudioVolumeLevelSupport;
    }

    /**
     * Converts an instance to a builder.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Constructs an instance from a [Device Features] operand.
     *
     * Bit 7 of [Device Features] is currently ignored. It indicates whether the operand spans more
     * than one byte, but only the first byte contains information as of CEC 2.0.
     *
     * @param deviceFeaturesOperand The [Device Features] operand to parse.
     * @return Instance representing the [Device Features] operand.
     */
    @NonNull
    public static DeviceFeatures fromOperand(@NonNull byte[] deviceFeaturesOperand) {
        Builder builder = new Builder(FEATURE_SUPPORT_UNKNOWN);

        // Read feature support status from the bits of [Device Features]
        if (deviceFeaturesOperand.length >= 1) {
            byte b = deviceFeaturesOperand[0];
            builder
                    .setRecordTvScreenSupport(bitToFeatureSupportStatus(((b >> 6) & 1) == 1))
                    .setSetOsdStringSupport(bitToFeatureSupportStatus(((b >> 5) & 1) == 1))
                    .setDeckControlSupport(bitToFeatureSupportStatus(((b >> 4) & 1) == 1))
                    .setSetAudioRateSupport(bitToFeatureSupportStatus(((b >> 3) & 1) == 1))
                    .setArcTxSupport(bitToFeatureSupportStatus(((b >> 2) & 1) == 1))
                    .setArcRxSupport(bitToFeatureSupportStatus(((b >> 1) & 1) == 1))
                    .setSetAudioVolumeLevelSupport(bitToFeatureSupportStatus((b & 1) == 1));
        }
        return builder.build();
    }

    /**
     * Returns the input that is not {@link #FEATURE_SUPPORT_UNKNOWN}. If neither is equal to
     * {@link #FEATURE_SUPPORT_UNKNOWN}, returns the second input.
     */
    private static @FeatureSupportStatus int updateFeatureSupportStatus(
            @FeatureSupportStatus int oldStatus, @FeatureSupportStatus int newStatus) {
        if (newStatus == FEATURE_SUPPORT_UNKNOWN) {
            return oldStatus;
        } else {
            return newStatus;
        }
    }

    /**
     * Returns the [Device Features] operand corresponding to this instance.
     * {@link #FEATURE_SUPPORT_UNKNOWN} maps to 0, indicating no support.
     *
     * As of CEC 2.0, the returned byte array will always be of length 1.
     */
    @NonNull
    public byte[] toOperand() {
        byte result = 0;

        if (mRecordTvScreenSupport == FEATURE_SUPPORTED) result |= (byte) (1 << 6);
        if (mSetOsdStringSupport == FEATURE_SUPPORTED) result = (byte) (1 << 5);
        if (mDeckControlSupport == FEATURE_SUPPORTED) result = (byte) (1 << 4);
        if (mSetAudioRateSupport == FEATURE_SUPPORTED) result = (byte) (1 << 3);
        if (mArcTxSupport == FEATURE_SUPPORTED) result = (byte) (1 << 2);
        if (mArcRxSupport == FEATURE_SUPPORTED) result = (byte) (1 << 1);
        if (mSetAudioVolumeLevelSupport == FEATURE_SUPPORTED) result = (byte) 1;

        return new byte[]{ result };
    }

    @FeatureSupportStatus
    private static int bitToFeatureSupportStatus(boolean bit) {
        return bit ? FEATURE_SUPPORTED : FEATURE_NOT_SUPPORTED;
    }

    /**
     * Returns whether the device is a TV that supports <Record TV Screen>.
     */
    @FeatureSupportStatus
    public int getRecordTvScreenSupport() {
        return mRecordTvScreenSupport;
    }

    /**
     * Returns whether the device is a TV that supports <Set OSD String>.
     */
    @FeatureSupportStatus
    public int getSetOsdStringSupport() {
        return mSetOsdStringSupport;
    }

    /**
     * Returns whether the device supports being controlled by Deck Control.
     */
    @FeatureSupportStatus
    public int getDeckControlSupport() {
        return mDeckControlSupport;
    }

    /**
     * Returns whether the device is a Source that supports <Set Audio Rate>.
     */
    @FeatureSupportStatus
    public int getSetAudioRateSupport() {
        return mSetAudioRateSupport;
    }

    /**
     * Returns whether the device is a Sink that supports ARC Tx.
     */
    @FeatureSupportStatus
    public int getArcTxSupport() {
        return mArcTxSupport;
    }

    /**
     * Returns whether the device is a Source that supports ARC Rx.
     */
    @FeatureSupportStatus
    public int getArcRxSupport() {
        return mArcRxSupport;
    }

    /**
     * Returns whether the device supports <Set Audio Volume Level>.
     */
    @FeatureSupportStatus
    public int getSetAudioVolumeLevelSupport() {
        return mSetAudioVolumeLevelSupport;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof DeviceFeatures)) {
            return false;
        }

        DeviceFeatures other = (DeviceFeatures) obj;
        return mRecordTvScreenSupport == other.mRecordTvScreenSupport
                && mSetOsdStringSupport == other.mSetOsdStringSupport
                && mDeckControlSupport == other.mDeckControlSupport
                && mSetAudioRateSupport == other.mSetAudioRateSupport
                && mArcTxSupport == other.mArcTxSupport
                && mArcRxSupport == other.mArcRxSupport
                && mSetAudioVolumeLevelSupport == other.mSetAudioVolumeLevelSupport;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                mRecordTvScreenSupport,
                mSetOsdStringSupport,
                mDeckControlSupport,
                mSetAudioRateSupport,
                mArcTxSupport,
                mArcRxSupport,
                mSetAudioVolumeLevelSupport
        );
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Device features: ");
        s.append("record_tv_screen: ")
                .append(featureSupportStatusToString(mRecordTvScreenSupport)).append(" ");
        s.append("set_osd_string: ")
                .append(featureSupportStatusToString(mSetOsdStringSupport)).append(" ");
        s.append("deck_control: ")
                .append(featureSupportStatusToString(mDeckControlSupport)).append(" ");
        s.append("set_audio_rate: ")
                .append(featureSupportStatusToString(mSetAudioRateSupport)).append(" ");
        s.append("arc_tx: ")
                .append(featureSupportStatusToString(mArcTxSupport)).append(" ");
        s.append("arc_rx: ")
                .append(featureSupportStatusToString(mArcRxSupport)).append(" ");
        s.append("set_audio_volume_level: ")
                .append(featureSupportStatusToString(mSetAudioVolumeLevelSupport)).append(" ");
        return s.toString();
    }

    @NonNull
    private static String featureSupportStatusToString(@FeatureSupportStatus int status) {
        switch (status) {
            case FEATURE_SUPPORTED:
                return "Y";
            case FEATURE_NOT_SUPPORTED:
                return "N";
            case FEATURE_SUPPORT_UNKNOWN:
            default:
                return "?";
        }
    }

    /**
     * Builder for {@link DeviceFeatures} instances.
     */
    public static final class Builder {
        @FeatureSupportStatus private int mRecordTvScreenSupport;
        @FeatureSupportStatus private int mOsdStringSupport;
        @FeatureSupportStatus private int mDeckControlSupport;
        @FeatureSupportStatus private int mSetAudioRateSupport;
        @FeatureSupportStatus private int mArcTxSupport;
        @FeatureSupportStatus private int mArcRxSupport;
        @FeatureSupportStatus private int mSetAudioVolumeLevelSupport;

        private Builder(@FeatureSupportStatus int defaultFeatureSupportStatus) {
            mRecordTvScreenSupport = defaultFeatureSupportStatus;
            mOsdStringSupport = defaultFeatureSupportStatus;
            mDeckControlSupport = defaultFeatureSupportStatus;
            mSetAudioRateSupport = defaultFeatureSupportStatus;
            mArcTxSupport = defaultFeatureSupportStatus;
            mArcRxSupport = defaultFeatureSupportStatus;
            mSetAudioVolumeLevelSupport = defaultFeatureSupportStatus;
        }

        private Builder(DeviceFeatures info) {
            mRecordTvScreenSupport = info.getRecordTvScreenSupport();
            mOsdStringSupport = info.getSetOsdStringSupport();
            mDeckControlSupport = info.getDeckControlSupport();
            mSetAudioRateSupport = info.getSetAudioRateSupport();
            mArcTxSupport = info.getArcTxSupport();
            mArcRxSupport = info.getArcRxSupport();
            mSetAudioVolumeLevelSupport = info.getSetAudioVolumeLevelSupport();
        }

        /**
         * Creates a new {@link DeviceFeatures} object.
         */
        public DeviceFeatures build() {
            return new DeviceFeatures(this);
        }

        /**
         * Sets the value for {@link #getRecordTvScreenSupport()}.
         */
        @NonNull
        public Builder setRecordTvScreenSupport(@FeatureSupportStatus int recordTvScreenSupport) {
            mRecordTvScreenSupport = recordTvScreenSupport;
            return this;
        }

        /**
         * Sets the value for {@link #getSetOsdStringSupport()}.
         */
        @NonNull
        public Builder setSetOsdStringSupport(@FeatureSupportStatus int setOsdStringSupport) {
            mOsdStringSupport = setOsdStringSupport;
            return this;
        }

        /**
         * Sets the value for {@link #getDeckControlSupport()}.
         */
        @NonNull
        public Builder setDeckControlSupport(@FeatureSupportStatus int deckControlSupport) {
            mDeckControlSupport = deckControlSupport;
            return this;
        }

        /**
         * Sets the value for {@link #getSetAudioRateSupport()}.
         */
        @NonNull
        public Builder setSetAudioRateSupport(@FeatureSupportStatus int setAudioRateSupport) {
            mSetAudioRateSupport = setAudioRateSupport;
            return this;
        }

        /**
         * Sets the value for {@link #getArcTxSupport()}.
         */
        @NonNull
        public Builder setArcTxSupport(@FeatureSupportStatus int arcTxSupport) {
            mArcTxSupport = arcTxSupport;
            return this;
        }

        /**
         * Sets the value for {@link #getArcRxSupport()}.
         */
        @NonNull
        public Builder setArcRxSupport(@FeatureSupportStatus int arcRxSupport) {
            mArcRxSupport = arcRxSupport;
            return this;
        }

        /**
         * Sets the value for {@link #getSetAudioRateSupport()}.
         */
        @NonNull
        public Builder setSetAudioVolumeLevelSupport(
                @FeatureSupportStatus int setAudioVolumeLevelSupport) {
            mSetAudioVolumeLevelSupport = setAudioVolumeLevelSupport;
            return this;
        }

        /**
         * Updates all fields with those in a 'new' instance of {@link DeviceFeatures}.
         * All fields are replaced with those in the new instance, except when the field is
         * {@link #FEATURE_SUPPORT_UNKNOWN} in the new instance.
         */
        @NonNull
        public Builder update(DeviceFeatures newDeviceFeatures) {
            mRecordTvScreenSupport = updateFeatureSupportStatus(mRecordTvScreenSupport,
                    newDeviceFeatures.getRecordTvScreenSupport());
            mOsdStringSupport = updateFeatureSupportStatus(mOsdStringSupport,
                    newDeviceFeatures.getSetOsdStringSupport());
            mDeckControlSupport = updateFeatureSupportStatus(mDeckControlSupport,
                    newDeviceFeatures.getDeckControlSupport());
            mSetAudioRateSupport = updateFeatureSupportStatus(mSetAudioRateSupport,
                    newDeviceFeatures.getSetAudioRateSupport());
            mArcTxSupport = updateFeatureSupportStatus(mArcTxSupport,
                    newDeviceFeatures.getArcTxSupport());
            mArcRxSupport = updateFeatureSupportStatus(mArcRxSupport,
                    newDeviceFeatures.getArcRxSupport());
            mSetAudioVolumeLevelSupport = updateFeatureSupportStatus(mSetAudioVolumeLevelSupport,
                    newDeviceFeatures.getSetAudioVolumeLevelSupport());
            return this;
        }
    }
}
