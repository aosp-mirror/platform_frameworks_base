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

package android.media.tv.tuner.frontend;

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Code rate for DVBS.
 *
 * @hide
 */
@SystemApi
public class DvbsCodeRate {
    private final long mInnerFec;
    private final boolean mIsLinear;
    private final boolean mIsShortFrames;
    private final int mBitsPer1000Symbol;

    private DvbsCodeRate(long fec, boolean isLinear, boolean isShortFrames, int bitsPer1000Symbol) {
        mInnerFec = fec;
        mIsLinear = isLinear;
        mIsShortFrames = isShortFrames;
        mBitsPer1000Symbol = bitsPer1000Symbol;
    }

    /**
     * Gets inner FEC.
     */
    @FrontendSettings.InnerFec
    public long getInnerFec() {
        return mInnerFec;
    }
    /**
     * Checks whether it's linear.
     */
    public boolean isLinear() {
        return mIsLinear;
    }
    /**
     * Checks whether short frame enabled.
     */
    public boolean isShortFrameEnabled() {
        return mIsShortFrames;
    }
    /**
     * Gets bits number in 1000 symbols. 0 by default.
     */
    public int getBitsPer1000Symbol() {
        return mBitsPer1000Symbol;
    }

    /**
     * Creates a builder for {@link DvbsCodeRate}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DvbsCodeRate}.
     */
    public static class Builder {
        private long mFec;
        private boolean mIsLinear;
        private boolean mIsShortFrames;
        private int mBitsPer1000Symbol;

        private Builder() {
        }

        /**
         * Sets inner FEC.
         */
        @NonNull
        public Builder setInnerFec(@FrontendSettings.InnerFec long fec) {
            mFec = fec;
            return this;
        }
        /**
         * Sets whether it's linear.
         */
        @NonNull
        public Builder setLinear(boolean isLinear) {
            mIsLinear = isLinear;
            return this;
        }
        /**
         * Sets whether short frame enabled.
         */
        @NonNull
        public Builder setShortFrameEnabled(boolean isShortFrames) {
            mIsShortFrames = isShortFrames;
            return this;
        }
        /**
         * Sets bits number in 1000 symbols.
         */
        @NonNull
        public Builder setBitsPer1000Symbol(int bitsPer1000Symbol) {
            mBitsPer1000Symbol = bitsPer1000Symbol;
            return this;
        }

        /**
         * Builds a {@link DvbsCodeRate} object.
         */
        @NonNull
        public DvbsCodeRate build() {
            return new DvbsCodeRate(mFec, mIsLinear, mIsShortFrames, mBitsPer1000Symbol);
        }
    }
}
