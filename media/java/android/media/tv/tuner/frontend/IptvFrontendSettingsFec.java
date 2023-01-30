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
import android.annotation.SystemApi;
import android.hardware.tv.tuner.FrontendIptvSettingsFecType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * FEC (Forward Error Correction) for IPTV.
 *
 * @hide
 */
@SystemApi
public class IptvFrontendSettingsFec {
    /** @hide */
    @IntDef(prefix = "FEC_TYPE_",
            value = {FEC_TYPE_UNDEFINED, FEC_TYPE_COLUMN, FEC_TYPE_ROW, FEC_TYPE_COLUMN_ROW})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FecType {}

    /**
     * FEC (Forward Error Correction) type UNDEFINED.
     */
    public static final int FEC_TYPE_UNDEFINED = FrontendIptvSettingsFecType.UNDEFINED;

    /**
     * FEC (Forward Error Correction) type Column.
     */
    public static final int FEC_TYPE_COLUMN = FrontendIptvSettingsFecType.COLUMN;

    /**
     * FEC (Forward Error Correction) type ROW.
     */
    public static final int FEC_TYPE_ROW = FrontendIptvSettingsFecType.ROW;

    /**
     * FEC (Forward Error Correction) type Column Row.
     */
    public static final int FEC_TYPE_COLUMN_ROW = FrontendIptvSettingsFecType.COLUMN_ROW;

    private final int mFecType;
    private final int mFecRowNum;
    private final int mFecColNum;

    private IptvFrontendSettingsFec(@FecType int fecType, int fecRowNum, int fecColNum) {
        mFecType = fecType;
        mFecRowNum = fecRowNum;
        mFecColNum = fecColNum;
    }

    /**
     * Gets the FEC (Forward Error Correction) type.
     */
    @FecType
    public int getFecType() {
        return mFecType;
    }

    /**
     * Get the FEC (Forward Error Correction) row number.
     */
    @IntRange(from = 0)
    public int getFecRowNum() {
        return mFecRowNum;
    }

    /**
     * Gets the FEC (Forward Error Correction) column number.
     */
    @IntRange(from = 0)
    public int getFecColNum() {
        return mFecColNum;
    }

    /**
     * Creates a builder for {@link IptvFrontendSettingsFec}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link IptvFrontendSettingsFec}.
     */
    public static final class Builder {
        private int mFecType;
        private int mFecRowNum;
        private int mFecColNum;

        private Builder() {
        }

        /**
         * Sets the FEC (Forward Error Correction) type
         */
        @NonNull
        public Builder setFecType(@FecType int fecType) {
            mFecType = fecType;
            return this;
        }
        /**
         * Sets the FEC (Forward Error Correction) row number.
         */
        @NonNull
        public Builder setFecRowNum(@IntRange(from = 0) int fecRowNum) {
            mFecRowNum = fecRowNum;
            return this;
        }
        /**
         * Sets the FEC (Forward Error Correction) column number.
         */
        @NonNull
        public Builder setFecColNum(@IntRange(from = 0) int fecColNum) {
            mFecColNum = fecColNum;
            return this;
        }

        /**
         * Builds a {@link IptvFrontendSettingsFec} object.
         */
        @NonNull
        public IptvFrontendSettingsFec build() {
            return new IptvFrontendSettingsFec(mFecType, mFecRowNum, mFecColNum);
        }
    }
}
