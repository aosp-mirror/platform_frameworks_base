/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.asllib;

import java.util.Map;

/**
 * Data label representation with data shared and data collected maps containing zero or more
 * {@link DataCategory}
 */
public class DataLabel {
    private final Map<String, DataCategory> mDataAccessed;
    private final Map<String, DataCategory> mDataCollected;
    private final Map<String, DataCategory> mDataShared;

    public DataLabel(
            Map<String, DataCategory> dataAccessed,
            Map<String, DataCategory> dataCollected,
            Map<String, DataCategory> dataShared) {
        mDataAccessed = dataAccessed;
        mDataCollected = dataCollected;
        mDataShared = dataShared;
    }

    /**
     * Returns the data accessed {@link Map} of {@link
     * com.android.asllib.DataCategoryConstants} to {@link DataCategory}
     */
    public Map<String, DataCategory> getDataAccessed() {
        return mDataAccessed;
    }

    /**
     * Returns the data collected {@link Map} of {@link
     * com.android.asllib.DataCategoryConstants} to {@link DataCategory}
     */
    public Map<String, DataCategory> getDataCollected() {
        return mDataCollected;
    }

    /**
     * Returns the data shared {@link Map} of {@link
     * com.android.asllib.DataCategoryConstants} to {@link DataCategory}
     */
    public Map<String, DataCategory> getDataShared() {
        return mDataShared;
    }
}
