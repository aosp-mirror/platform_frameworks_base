/*
 * Copyright 2022 The Android Open Source Project
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

package android.media.tv.tuner;

import android.annotation.SystemApi;
import android.media.tv.tuner.DemuxCapabilities.FilterCapabilities;

/**
 * This class is used to specify information of a demux.
 *
 * @hide
 */
@SystemApi
public class DemuxInfo {
    // Bitwise OR of filter types
    private int mFilterTypes;

    public DemuxInfo(@FilterCapabilities int filterTypes) {
        setFilterTypes(filterTypes);
    }

    /**
     * Gets the filter types
     *
     * @return the filter types
     */
    @FilterCapabilities
    public int getFilterTypes() {
        return mFilterTypes;
    }

    /**
     * Sets the filter types
     *
     * @param filterTypes the filter types to set
     */
    public void setFilterTypes(@FilterCapabilities int filterTypes) {
        mFilterTypes = filterTypes;
    }
}
