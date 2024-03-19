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

import java.util.Set;

/**
 * Data usage type representation. Types are specific to a {@link DataCategory} and contains
 * metadata related to the data usage purpose.
 */
public class DataType {

    private final Set<Integer> mPurposeSet;
    private final Boolean mIsCollectionOptional;
    private final Boolean mIsSharingOptional;
    private final Boolean mEphemeral;

    private DataType(
            Set<Integer> purposeSet,
            Boolean isCollectionOptional,
            Boolean isSharingOptional,
            Boolean ephemeral) {
        this.mPurposeSet = purposeSet;
        this.mIsCollectionOptional = isCollectionOptional;
        this.mIsSharingOptional = isSharingOptional;
        this.mEphemeral = ephemeral;
    }

    /**
     * Returns {@link Set} of valid {@link Integer} purposes for using the associated data category
     * and type
     */
    public Set<Integer> getPurposeSet() {
        return mPurposeSet;
    }

    /**
     * For data-collected, returns {@code true} if data usage is user optional and {@code false} if
     * data usage is required. Should return {@code null} for data-accessed and data-shared.
     */
    public Boolean getIsCollectionOptional() {
        return mIsCollectionOptional;
    }

    /**
     * For data-shared, returns {@code true} if data usage is user optional and {@code false} if
     * data usage is required. Should return {@code null} for data-accessed and data-collected.
     */
    public Boolean getIsSharingOptional() {
        return mIsSharingOptional;
    }

    /**
     * For data-collected, returns {@code true} if data usage is user optional and {@code false} if
     * data usage is processed ephemerally. Should return {@code null} for data-shared.
     */
    public Boolean getEphemeral() {
        return mEphemeral;
    }
}

