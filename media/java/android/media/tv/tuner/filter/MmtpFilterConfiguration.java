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
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.media.tv.tuner.TunerUtils;

/**
 * Filter configuration for a MMTP filter.
 *
 * @hide
 */
@SystemApi
public final class MmtpFilterConfiguration extends FilterConfiguration {
    private final int mMmtpPid;

    private MmtpFilterConfiguration(Settings settings, int mmtpPid) {
        super(settings);
        mMmtpPid = mmtpPid;
    }

    @Override
    public int getType() {
        return Filter.TYPE_MMTP;
    }

    /**
     * Gets MMTP Packet ID.
     *
     * <p>Packet ID is used to specify packets in MMTP.
     */
    public int getMmtpPacketId() {
        return mMmtpPid;
    }

    /**
     * Creates a builder for {@link IpFilterConfiguration}.
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
     * Builder for {@link IpFilterConfiguration}.
     */
    public static final class Builder {
        private int mMmtpPid;
        private Settings mSettings;

        private Builder() {
        }

        /**
         * Sets MMTP Packet ID.
         */
        @NonNull
        public Builder setMmtpPacketId(int mmtpPid) {
            mMmtpPid = mmtpPid;
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
         * Builds a {@link IpFilterConfiguration} object.
         */
        @NonNull
        public MmtpFilterConfiguration build() {
            return new MmtpFilterConfiguration(mSettings, mMmtpPid);
        }
    }
}
