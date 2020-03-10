/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Filter configuration for a ALP filter.
 *
 * @hide
 */
@SystemApi
public final class AlpFilterConfiguration extends FilterConfiguration {
    /**
     * IPv4 packet type.
     */
    public static final int PACKET_TYPE_IPV4 = 0;
    /**
     * Compressed packet type.
     */
    public static final int PACKET_TYPE_COMPRESSED = 2;
    /**
     * Signaling packet type.
     */
    public static final int PACKET_TYPE_SIGNALING = 4;
    /**
     * Extension packet type.
     */
    public static final int PACKET_TYPE_EXTENSION = 6;
    /**
     * MPEG-2 TS packet type.
     */
    public static final int PACKET_TYPE_MPEG2_TS = 7;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "LENGTH_TYPE_", value =
            {LENGTH_TYPE_UNDEFINED, LENGTH_TYPE_WITHOUT_ADDITIONAL_HEADER,
            LENGTH_TYPE_WITH_ADDITIONAL_HEADER})
    public @interface LengthType {}

    /**
     * Length type not defined.
     */
    public static final int LENGTH_TYPE_UNDEFINED = Constants.DemuxAlpLengthType.UNDEFINED;
    /**
     * Length does NOT include additional header.
     */
    public static final int LENGTH_TYPE_WITHOUT_ADDITIONAL_HEADER =
            Constants.DemuxAlpLengthType.WITHOUT_ADDITIONAL_HEADER;
    /**
     * Length includes additional header.
     */
    public static final int LENGTH_TYPE_WITH_ADDITIONAL_HEADER =
            Constants.DemuxAlpLengthType.WITH_ADDITIONAL_HEADER;


    private final int mPacketType;
    private final int mLengthType;

    private AlpFilterConfiguration(Settings settings, int packetType, int lengthType) {
        super(settings);
        mPacketType = packetType;
        mLengthType = lengthType;
    }

    @Override
    public int getType() {
        return Filter.TYPE_ALP;
    }

    /**
     * Gets packet type.
     *
     * <p>The meaning of each packet type value is shown in ATSC A/330:2019 table 5.2.
     */
    public int getPacketType() {
        return mPacketType;
    }
    /**
     * Gets length type.
     */
    @LengthType
    public int getLengthType() {
        return mLengthType;
    }

    /**
     * Creates a builder for {@link AlpFilterConfiguration}.
     *
     * @param context the context of the caller.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @NonNull
    public static Builder builder(@NonNull Context context) {
        TunerUtils.checkTunerPermission(context);
        return new Builder();
    }

    /**
     * Builder for {@link AlpFilterConfiguration}.
     */
    public static final class Builder {
        private int mPacketType;
        private int mLengthType;
        private Settings mSettings;

        private Builder() {
        }

        /**
         * Sets packet type.
         *
         * <p>The meaning of each packet type value is shown in ATSC A/330:2019 table 5.2.
         */
        @NonNull
        public Builder setPacketType(int packetType) {
            mPacketType = packetType;
            return this;
        }
        /**
         * Sets length type.
         */
        @NonNull
        public Builder setLengthType(@LengthType int lengthType) {
            mLengthType = lengthType;
            return this;
        }

        /**
         * Sets filter settings.
         */
        @NonNull
        public Builder setSettings(@Nullable Settings settings) {
            mSettings = settings;
            return this;
        }

        /**
         * Builds a {@link AlpFilterConfiguration} object.
         */
        @NonNull
        public AlpFilterConfiguration build() {
            return new AlpFilterConfiguration(mSettings, mPacketType, mLengthType);
        }
    }
}
