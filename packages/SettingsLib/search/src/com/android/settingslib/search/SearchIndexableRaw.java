/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.search;

import android.content.Context;
import android.provider.SearchIndexableData;

/**
 * Indexable raw data for Search.
 *
 * This is the raw data used by the Indexer and should match its data model.
 *
 * See {@link Indexable} and {@link android.provider.SearchIndexableResource}.
 */
public class SearchIndexableRaw extends SearchIndexableData {

    /**
     * Title's raw data.
     */
    public String title;

    /**
     * Summary's raw data when the data is "ON".
     */
    public String summaryOn;

    /**
     * Summary's raw data when the data is "OFF".
     */
    public String summaryOff;

    /**
     * Entries associated with the raw data (when the data can have several values).
     */
    public String entries;

    /**
     * Keywords' raw data.
     */
    public String keywords;

    /**
     * Fragment's or Activity's title associated with the raw data.
     */
    public String screenTitle;

    public SearchIndexableRaw(Context context) {
        super(context);
    }
}
