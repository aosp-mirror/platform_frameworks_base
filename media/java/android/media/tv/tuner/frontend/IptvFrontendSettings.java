/*
 * Copyright 2023 The Android Open Source Project
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

package android.media.tv.tuner.frontend;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.FrontendIptvSettingsIgmp;
import android.hardware.tv.tuner.FrontendIptvSettingsProtocol;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for IPTV.
 *
 * @hide
 */
@SystemApi
public final class IptvFrontendSettings extends FrontendSettings {
    /** @hide */
    @IntDef(prefix = "PROTOCOL_",
            value = {PROTOCOL_UNDEFINED, PROTOCOL_UDP, PROTOCOL_RTP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Protocol {}

    /**
     * IP protocol type UNDEFINED.
     */
    public static final int PROTOCOL_UNDEFINED = FrontendIptvSettingsProtocol.UNDEFINED;

    /**
     * IP protocol type UDP (User Datagram Protocol).
     */
    public static final int PROTOCOL_UDP = FrontendIptvSettingsProtocol.UDP;

    /**
     * IP protocol type RTP (Real-time Transport Protocol).
     */
    public static final int PROTOCOL_RTP = FrontendIptvSettingsProtocol.RTP;

    /** @hide */
    @IntDef(prefix = "IGMP_",
            value = {IGMP_UNDEFINED, IGMP_V1, IGMP_V2, IGMP_V3})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Igmp {}

    /**
     * IGMP (Internet Group Management Protocol) UNDEFINED.
     */
    public static final int IGMP_UNDEFINED = FrontendIptvSettingsIgmp.UNDEFINED;

    /**
     * IGMP (Internet Group Management Protocol) V1.
     */
    public static final int IGMP_V1 = FrontendIptvSettingsIgmp.V1;

    /**
     * IGMP (Internet Group Management Protocol) V2.
     */
    public static final int IGMP_V2 = FrontendIptvSettingsIgmp.V2;

    /**
     * IGMP (Internet Group Management Protocol) V3.
     */
    public static final int IGMP_V3 = FrontendIptvSettingsIgmp.V3;

    private final byte[] mSrcIpAddress;
    private final byte[] mDstIpAddress;
    private final int mSrcPort;
    private final int mDstPort;
    private final IptvFrontendSettingsFec mFec;
    private final int mProtocol;
    private final int mIgmp;
    private final long mBitrate;
    private final String mContentUrl;

    private IptvFrontendSettings(@NonNull byte[] srcIpAddress, @NonNull byte[] dstIpAddress,
            int srcPort, int dstPort, @NonNull IptvFrontendSettingsFec fec, int protocol, int igmp,
            long bitrate, @NonNull String contentUrl) {
        super(0);
        mSrcIpAddress = srcIpAddress;
        mDstIpAddress = dstIpAddress;
        mSrcPort = srcPort;
        mDstPort = dstPort;
        mFec = fec;
        mProtocol = protocol;
        mIgmp = igmp;
        mBitrate = bitrate;
        mContentUrl = contentUrl;
    }

    /**
     * Gets the source IP address.
     */
    @Size(min = 4, max = 16)
    @NonNull
    public byte[] getSrcIpAddress() {
        return mSrcIpAddress;
    }

    /**
     * Gets the destination IP address.
     */
    @Size(min = 4, max = 16)
    @NonNull
    public byte[] getDstIpAddress() {
        return mDstIpAddress;
    }

    /**
     * Gets the source port.
     */
    public int getSrcPort() {
        return mSrcPort;
    }

    /**
     * Gets the destination port.
     */
    public int getDstPort() {
        return mDstPort;
    }

    /**
     * Gets FEC (Forward Error Correction).
     */
    @Nullable
    public IptvFrontendSettingsFec getFec() {
        return mFec;
    }

    /**
     * Gets the protocol.
     */
    @Protocol
    public int getProtocol() {
        return mProtocol;
    }

    /**
     * Gets the IGMP (Internet Group Management Protocol).
     */
    @Igmp
    public int getIgmp() {
        return mIgmp;
    }

    /**
     * Gets the bitrate.
     */
    @IntRange(from = 0)
    public long getBitrate() {
        return mBitrate;
    }

    /**
     * Gets the contentUrl
     * contentUrl is a source URL in the format protocol://ip:port containing data
     */
    @NonNull
    public String getContentUrl() {
        return mContentUrl;
    }

    /**
     * Builder for {@link IptvFrontendSettings}.
     */
    public static final class Builder {
        private byte[] mSrcIpAddress = {0, 0, 0, 0};
        private byte[] mDstIpAddress = {0, 0, 0, 0};
        private int mSrcPort = 0;
        private int mDstPort = 0;
        private IptvFrontendSettingsFec mFec = null;
        private int mProtocol = FrontendIptvSettingsProtocol.UNDEFINED;
        private int mIgmp = FrontendIptvSettingsIgmp.UNDEFINED;
        private long mBitrate = 0;
        private String mContentUrl = "";

        public Builder() {
        }

        /**
         * Sets the source IP address.
         *
         * <p>Default value is 0.0.0.0, an invalid IP address.
         */
        @NonNull
        public Builder setSrcIpAddress(@NonNull  byte[] srcIpAddress) {
            mSrcIpAddress = srcIpAddress;
            return this;
        }

        /**
         * Sets the destination IP address.
         *
         * <p>Default value is 0.0.0.0, an invalid IP address.
         */
        @NonNull
        public Builder setDstIpAddress(@NonNull  byte[] dstIpAddress) {
            mDstIpAddress = dstIpAddress;
            return this;
        }

        /**
         * Sets the source IP port.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setSrcPort(int srcPort) {
            mSrcPort = srcPort;
            return this;
        }

        /**
         * Sets the destination IP port.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setDstPort(int dstPort) {
            mDstPort = dstPort;
            return this;
        }

        /**
         * Sets the FEC (Forward Error Correction).
         *
         * <p>Default value is {@code null}.
         */
        @NonNull
        public Builder setFec(@Nullable IptvFrontendSettingsFec fec) {
            mFec = fec;
            return this;
        }

        /**
         * Sets the protocol.
         *
         * <p>Default value is {@link #PROTOCOL_UNDEFINED}.
         */
        @NonNull
        public Builder setProtocol(@Protocol int protocol) {
            mProtocol = protocol;
            return this;
        }

        /**
         * Sets the IGMP (Internet Group Management Protocol).
         *
         * <p>Default value is {@link #IGMP_UNDEFINED}.
         */
        @NonNull
        public Builder setIgmp(@Igmp int igmp) {
            mIgmp = igmp;
            return this;
        }

        /**
         * Sets the bitrate.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setBitrate(@IntRange(from = 0) long bitrate) {
            mBitrate = bitrate;
            return this;
        }

        /**
         * Sets the contentUrl.
         *
         * <p>Default value is "".
         */
        @NonNull
        public Builder setContentUrl(@NonNull String contentUrl) {
            mContentUrl = contentUrl;
            return this;
        }

        /**
         * Builds a {@link IptvFrontendSettings} object.
         */
        @NonNull
        public IptvFrontendSettings build() {
            return new IptvFrontendSettings(mSrcIpAddress, mDstIpAddress, mSrcPort, mDstPort,
                    mFec, mProtocol, mIgmp, mBitrate, mContentUrl);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_IPTV;
    }
}
