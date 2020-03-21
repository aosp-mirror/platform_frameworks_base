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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

/**
 * Filter configuration for a TLV filter.
 *
 * @hide
 */
@SystemApi
public final class TlvFilterConfiguration extends FilterConfiguration {
    /**
     * IPv4 packet type.
     */
    public static final int PACKET_TYPE_IPV4 = 0x01;
    /**
     * IPv6 packet type.
     */
    public static final int PACKET_TYPE_IPV6 = 0x02;
    /**
     * Compressed packet type.
     */
    public static final int PACKET_TYPE_COMPRESSED = 0x03;
    /**
     * Signaling packet type.
     */
    public static final int PACKET_TYPE_SIGNALING = 0xFE;
    /**
     * NULL packet type.
     */
    public static final int PACKET_TYPE_NULL = 0xFF;

    private final int mPacketType;
    private final boolean mIsCompressedIpPacket;
    private final boolean mPassthrough;

    private TlvFilterConfiguration(Settings settings, int packetType, boolean isCompressed,
            boolean passthrough) {
        super(settings);
        mPacketType = packetType;
        mIsCompressedIpPacket = isCompressed;
        mPassthrough = passthrough;
    }

    @Override
    public int getType() {
        return Filter.TYPE_TLV;
    }

    /**
     * Gets packet type.
     *
     * <p>The description of each packet type value is shown in ITU-R BT.1869 table 2.
     */
    public int getPacketType() {
        return mPacketType;
    }
    /**
     * Checks whether the data is compressed IP packet.
     *
     * @return {@code true} if the filtered data is compressed IP packet; {@code false} otherwise.
     */
    public boolean isCompressedIpPacket() {
        return mIsCompressedIpPacket;
    }
    /**
     * Checks whether it's passthrough.
     *
     * @return {@code true} if the data from TLV subtype go to next filter directly;
     *         {@code false} otherwise.
     */
    public boolean isPassthrough() {
        return mPassthrough;
    }

    /**
     * Creates a builder for {@link TlvFilterConfiguration}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link TlvFilterConfiguration}.
     */
    public static final class Builder {
        private int mPacketType = PACKET_TYPE_NULL;
        private boolean mIsCompressedIpPacket = false;
        private boolean mPassthrough = false;
        private Settings mSettings;

        private Builder() {
        }

        /**
         * Sets packet type.
         *
         * <p>The description of each packet type value is shown in ITU-R BT.1869 table 2.
         * <p>Default value is {@link #PACKET_TYPE_NULL}.
         */
        @NonNull
        public Builder setPacketType(int packetType) {
            mPacketType = packetType;
            return this;
        }
        /**
         * Sets whether the data is compressed IP packet.
         *
         * <p>Default value is {@code false}.
         */
        @NonNull
        public Builder setCompressedIpPacket(boolean isCompressedIpPacket) {
            mIsCompressedIpPacket = isCompressedIpPacket;
            return this;
        }
        /**
         * Sets whether it's passthrough.
         *
         * <p>Default value is {@code false}.
         */
        @NonNull
        public Builder setPassthrough(boolean passthrough) {
            mPassthrough = passthrough;
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
         * Builds a {@link TlvFilterConfiguration} object.
         */
        @NonNull
        public TlvFilterConfiguration build() {
            return new TlvFilterConfiguration(
                    mSettings, mPacketType, mIsCompressedIpPacket, mPassthrough);
        }
    }
}
