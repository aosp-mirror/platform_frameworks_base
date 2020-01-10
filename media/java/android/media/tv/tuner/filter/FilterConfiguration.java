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
import android.annotation.Nullable;
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Filter configuration used to configure filters.
 *
 * @hide
 */
public abstract class FilterConfiguration {

    /** @hide */
    @IntDef({FILTER_TYPE_TS, FILTER_TYPE_MMTP, FILTER_TYPE_IP, FILTER_TYPE_TLV, FILTER_TYPE_ALP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterType {}

    /**
     * TS filter type.
     */
    public static final int FILTER_TYPE_TS = Constants.DemuxFilterMainType.TS;
    /**
     * MMTP filter type.
     */
    public static final int FILTER_TYPE_MMTP = Constants.DemuxFilterMainType.MMTP;
    /**
     * IP filter type.
     */
    public static final int FILTER_TYPE_IP = Constants.DemuxFilterMainType.IP;
    /**
     * TLV filter type.
     */
    public static final int FILTER_TYPE_TLV = Constants.DemuxFilterMainType.TLV;
    /**
     * ALP filter type.
     */
    public static final int FILTER_TYPE_ALP = Constants.DemuxFilterMainType.ALP;

    @Nullable
    private final Settings mSettings;

    /* package */ FilterConfiguration(Settings settings) {
        mSettings = settings;
    }

    /**
     * Gets filter configuration type.
     * @hide
     */
    @FilterType
    public abstract int getType();

    /** @hide */
    @Nullable
    public Settings getSettings() {
        return mSettings;
    }
}
