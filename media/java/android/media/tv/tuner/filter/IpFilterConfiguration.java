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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.media.tv.tuner.TunerVersionChecker;

/**
 * Filter configuration for a IP filter.
 *
 * @hide
 */
@SystemApi
public final class IpFilterConfiguration extends FilterConfiguration {
    /**
     * Undefined filter type.
     */
    public static final int INVALID_IP_FILTER_CONTEXT_ID =
            android.hardware.tv.tuner.V1_1.Constants.Constant.INVALID_IP_FILTER_CONTEXT_ID;

    private final byte[] mSrcIpAddress;
    private final byte[] mDstIpAddress;
    private final int mSrcPort;
    private final int mDstPort;
    private final boolean mPassthrough;
    private final int mIpFilterContextId;

    private IpFilterConfiguration(Settings settings, byte[] srcAddr, byte[] dstAddr, int srcPort,
            int dstPort, boolean passthrough, int ipCid) {
        super(settings);
        mSrcIpAddress = srcAddr;
        mDstIpAddress = dstAddr;
        mSrcPort = srcPort;
        mDstPort = dstPort;
        mPassthrough = passthrough;
        mIpFilterContextId = ipCid;
    }

    @Override
    public int getType() {
        return Filter.TYPE_IP;
    }

    /**
     * Gets source IP address.
     */
    @Size(min = 4, max = 16)
    @NonNull
    public byte[] getSrcIpAddress() {
        return mSrcIpAddress;
    }
    /**
     * Gets destination IP address.
     */
    @Size(min = 4, max = 16)
    @NonNull
    public byte[] getDstIpAddress() {
        return mDstIpAddress;
    }
    /**
     * Gets source port.
     */
    public int getSrcPort() {
        return mSrcPort;
    }
    /**
     * Gets destination port.
     */
    public int getDstPort() {
        return mDstPort;
    }
    /**
     * Checks whether the filter is passthrough.
     *
     * @return {@code true} if the data from IP subtype go to next filter directly;
     *         {@code false} otherwise.
     */
    public boolean isPassthrough() {
        return mPassthrough;
    }
    /**
     * Gets the ip filter context id. Default value is {@link #INVALID_IP_FILTER_CONTEXT_ID}.
     *
     * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would return
     * default value. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @IntRange(from = 0, to = 0xefff)
    public int getIpFilterContextId() {
        return mIpFilterContextId;
    }

    /**
     * Creates a builder for {@link IpFilterConfiguration}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link IpFilterConfiguration}.
     */
    public static final class Builder {
        private byte[] mSrcIpAddress = {0, 0, 0, 0};
        private byte[] mDstIpAddress = {0, 0, 0, 0};
        private int mSrcPort = 0;
        private int mDstPort = 0;
        private boolean mPassthrough = false;
        private Settings mSettings;
        private int mIpCid = INVALID_IP_FILTER_CONTEXT_ID;

        private Builder() {
        }

        /**
         * Sets source IP address.
         *
         * <p>Default value is 0.0.0.0, an invalid IP address.
         */
        @NonNull
        public Builder setSrcIpAddress(@NonNull byte[] srcIpAddress) {
            mSrcIpAddress = srcIpAddress;
            return this;
        }
        /**
         * Sets destination IP address.
         *
         * <p>Default value is 0.0.0.0, an invalid IP address.
         */
        @NonNull
        public Builder setDstIpAddress(@NonNull byte[] dstIpAddress) {
            mDstIpAddress = dstIpAddress;
            return this;
        }
        /**
         * Sets source port.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setSrcPort(int srcPort) {
            mSrcPort = srcPort;
            return this;
        }
        /**
         * Sets destination port.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setDstPort(int dstPort) {
            mDstPort = dstPort;
            return this;
        }
        /**
         * Sets passthrough.
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
         * Sets the ip filter context id. Default value is {@link #INVALID_IP_FILTER_CONTEXT_ID}.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         */
        @NonNull
        public Builder setIpFilterContextId(int ipContextId) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_1_1, "setIpFilterContextId")) {
                mIpCid = ipContextId;
            }
            return this;
        }

        /**
         * Builds a {@link IpFilterConfiguration} object.
         */
        @NonNull
        public IpFilterConfiguration build() {
            int ipAddrLength = mSrcIpAddress.length;
            if (ipAddrLength != mDstIpAddress.length || (ipAddrLength != 4 && ipAddrLength != 16)) {
                throw new IllegalArgumentException(
                    "The lengths of src and dst IP address must be 4 or 16 and must be the same."
                            + "srcLength=" + ipAddrLength + ", dstLength=" + mDstIpAddress.length);
            }
            return new IpFilterConfiguration(mSettings, mSrcIpAddress, mDstIpAddress, mSrcPort,
                    mDstPort, mPassthrough, mIpCid);
        }
    }
}
