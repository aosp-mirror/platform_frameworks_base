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

package com.android.asllib.marshallable;

import com.android.asllib.util.DataCategoryConstants;
import com.android.asllib.util.DataTypeConstants;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.List;
import java.util.Map;

/**
 * Data usage category representation containing one or more {@link DataType}. Valid category keys
 * are defined in {@link DataCategoryConstants}, each category has a valid set of types {@link
 * DataType}, which are mapped in {@link DataTypeConstants}
 */
public class DataCategory implements AslMarshallable {
    private final String mCategoryName;
    private final Map<String, DataType> mDataTypes;

    public DataCategory(String categoryName, Map<String, DataType> dataTypes) {
        this.mCategoryName = categoryName;
        this.mDataTypes = dataTypes;
    }

    public String getCategoryName() {
        return mCategoryName;
    }

    /** Return the type {@link Map} of String type key to {@link DataType} */

    public Map<String, DataType> getDataTypes() {
        return mDataTypes;
    }

    /** Creates on-device DOM element(s) from the {@link DataCategory}. */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element dataCategoryEle = XmlUtils.createPbundleEleWithName(doc, this.getCategoryName());
        for (DataType dataType : mDataTypes.values()) {
            XmlUtils.appendChildren(dataCategoryEle, dataType.toOdDomElements(doc));
        }
        return XmlUtils.listOf(dataCategoryEle);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        throw new IllegalStateException(
                "Turning DataCategory or DataType into human-readable DOM elements requires"
                        + " visibility into parent elements. The logic resides in DataLabels.");
    }
}
