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
 * Data usage category representation containing one or more {@link DataType}. Valid category keys
 * are defined in {@link DataCategoryConstants}, each category has a valid set of types {@link
 * DataType}, which are mapped in {@link DataTypeConstants}
 */
public class DataCategory {
    private final Map<String, DataType> mDataTypes;

    private DataCategory(Map<String, DataType> dataTypes) {
        this.mDataTypes = dataTypes;
    }

    /** Return the type {@link Map} of String type key to {@link DataType} */

    public Map<String, DataType> getDataTypes() {
        return mDataTypes;
    }

    /** Creates a {@link DataCategory} given map of {@param dataTypes}. */
    public static DataCategory create(Map<String, DataType> dataTypes) {
        return new DataCategory(dataTypes);
    }
}
