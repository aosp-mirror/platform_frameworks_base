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


/** Safety Label representation containing zero or more {@link DataCategory} for data shared */
public class SafetyLabel {

    private final long mVersion;
    private final DataLabel mDataLabel;

    private SafetyLabel(long version, DataLabel dataLabel) {
        this.mVersion = version;
        this.mDataLabel = dataLabel;
    }

    /** Returns the data label for the safety label */
    public DataLabel getDataLabel() {
        return mDataLabel;
    }

    public long getVersion() {
        return mVersion;
    }
}

