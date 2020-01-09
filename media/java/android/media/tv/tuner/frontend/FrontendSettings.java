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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for tune and scan operations.
 *
 * @hide
 */
@SystemApi
public abstract class FrontendSettings {
    /** @hide */
    @IntDef({TYPE_UNDEFINED, TYPE_ANALOG, TYPE_ATSC, TYPE_ATSC3, TYPE_DVBC, TYPE_DVBS, TYPE_DVBT,
            TYPE_ISDBS, TYPE_ISDBS3, TYPE_ISDBT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Undefined frontend type.
     */
    public static final int TYPE_UNDEFINED = Constants.FrontendType.UNDEFINED;
    /**
     * Analog frontend type.
     */
    public static final int TYPE_ANALOG = Constants.FrontendType.ANALOG;
    /**
     * Advanced Television Systems Committee (ATSC) frontend type.
     */
    public static final int TYPE_ATSC = Constants.FrontendType.ATSC;
    /**
     * Advanced Television Systems Committee 3.0 (ATSC-3) frontend type.
     */
    public static final int TYPE_ATSC3 = Constants.FrontendType.ATSC3;
    /**
     * Digital Video Broadcasting-Cable (DVB-C) frontend type.
     */
    public static final int TYPE_DVBC = Constants.FrontendType.DVBC;
    /**
     * Digital Video Broadcasting-Satellite (DVB-S) frontend type.
     */
    public static final int TYPE_DVBS = Constants.FrontendType.DVBS;
    /**
     * Digital Video Broadcasting-Terrestrial (DVB-T) frontend type.
     */
    public static final int TYPE_DVBT = Constants.FrontendType.DVBT;
    /**
     * Integrated Services Digital Broadcasting-Satellite (ISDB-S) frontend type.
     */
    public static final int TYPE_ISDBS = Constants.FrontendType.ISDBS;
    /**
     * Integrated Services Digital Broadcasting-Satellite 3 (ISDB-S3) frontend type.
     */
    public static final int TYPE_ISDBS3 = Constants.FrontendType.ISDBS3;
    /**
     * Integrated Services Digital Broadcasting-Terrestrial (ISDB-T) frontend type.
     */
    public static final int TYPE_ISDBT = Constants.FrontendType.ISDBT;

    private final int mFrequency;

    /** @hide */
    public FrontendSettings(int frequency) {
        mFrequency = frequency;
    }

    /**
     * Returns the frontend type.
     */
    @Type
    public abstract int getType();

    /**
     * Gets the frequency.
     *
     * @return the frequency in Hz.
     */
    public int getFrequency() {
        return mFrequency;
    }

    /**
     * Builder for {@link FrontendSettings}.
     *
     * @param <T> The subclass to be built.
     */
    public abstract static class Builder<T extends Builder<T>> {
        /* package */ int mFrequency;

        /* package */ Builder() {}

        /**
         * Sets frequency in Hz.
         */
        @NonNull
        @IntRange(from = 1)
        public T setFrequency(int frequency) {
            mFrequency = frequency;
            return self();
        }

        /* package */ abstract T self();
    }
}
