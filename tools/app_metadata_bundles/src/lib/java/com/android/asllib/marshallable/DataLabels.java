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
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.List;
import java.util.Map;

/**
 * Data label representation with data shared and data collected maps containing zero or more {@link
 * DataCategory}
 */
public class DataLabels implements AslMarshallable {
    private final Map<String, DataCategory> mDataAccessed;
    private final Map<String, DataCategory> mDataCollected;
    private final Map<String, DataCategory> mDataShared;

    public DataLabels(
            Map<String, DataCategory> dataAccessed,
            Map<String, DataCategory> dataCollected,
            Map<String, DataCategory> dataShared) {
        mDataAccessed = dataAccessed;
        mDataCollected = dataCollected;
        mDataShared = dataShared;
    }

    /**
     * Returns the data accessed {@link Map} of {@link DataCategoryConstants} to {@link
     * DataCategory}
     */
    public Map<String, DataCategory> getDataAccessed() {
        return mDataAccessed;
    }

    /**
     * Returns the data collected {@link Map} of {@link DataCategoryConstants} to {@link
     * DataCategory}
     */
    public Map<String, DataCategory> getDataCollected() {
        return mDataCollected;
    }

    /**
     * Returns the data shared {@link Map} of {@link DataCategoryConstants} to {@link DataCategory}
     */
    public Map<String, DataCategory> getDataShared() {
        return mDataShared;
    }

    /** Gets the on-device DOM element for the {@link DataLabels}. */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element dataLabelsEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_DATA_LABELS);

        maybeAppendDataUsages(doc, dataLabelsEle, mDataAccessed, XmlUtils.OD_NAME_DATA_ACCESSED);
        maybeAppendDataUsages(doc, dataLabelsEle, mDataCollected, XmlUtils.OD_NAME_DATA_COLLECTED);
        maybeAppendDataUsages(doc, dataLabelsEle, mDataShared, XmlUtils.OD_NAME_DATA_SHARED);

        return XmlUtils.listOf(dataLabelsEle);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        Element dataLabelsEle = doc.createElement(XmlUtils.HR_TAG_DATA_LABELS);
        maybeAppendHrDataUsages(doc, dataLabelsEle, mDataAccessed, XmlUtils.HR_TAG_DATA_ACCESSED);
        maybeAppendHrDataUsages(doc, dataLabelsEle, mDataCollected, XmlUtils.HR_TAG_DATA_COLLECTED);
        maybeAppendHrDataUsages(doc, dataLabelsEle, mDataShared, XmlUtils.HR_TAG_DATA_SHARED);
        return XmlUtils.listOf(dataLabelsEle);
    }

    private void maybeAppendDataUsages(
            Document doc,
            Element dataLabelsEle,
            Map<String, DataCategory> dataCategoriesMap,
            String dataUsageTypeName) {
        if (dataCategoriesMap.isEmpty()) {
            return;
        }
        Element dataUsageEle = XmlUtils.createPbundleEleWithName(doc, dataUsageTypeName);

        for (String dataCategoryName : dataCategoriesMap.keySet()) {
            Element dataCategoryEle = XmlUtils.createPbundleEleWithName(doc, dataCategoryName);
            DataCategory dataCategory = dataCategoriesMap.get(dataCategoryName);
            for (String dataTypeName : dataCategory.getDataTypes().keySet()) {
                DataType dataType = dataCategory.getDataTypes().get(dataTypeName);
                XmlUtils.appendChildren(dataCategoryEle, dataType.toOdDomElements(doc));
            }
            dataUsageEle.appendChild(dataCategoryEle);
        }
        dataLabelsEle.appendChild(dataUsageEle);
    }

    private void maybeAppendHrDataUsages(
            Document doc,
            Element dataLabelsEle,
            Map<String, DataCategory> dataCategoriesMap,
            String dataUsageTypeName) {
        if (dataCategoriesMap.isEmpty()) {
            return;
        }
        for (String dataCategoryName : dataCategoriesMap.keySet()) {
            DataCategory dataCategory = dataCategoriesMap.get(dataCategoryName);
            for (String dataTypeName : dataCategory.getDataTypes().keySet()) {
                DataType dataType = dataCategory.getDataTypes().get(dataTypeName);
                // XmlUtils.appendChildren(dataLabelsEle, dataType.toHrDomElements(doc));
                Element hrDataTypeEle = doc.createElement(dataUsageTypeName);
                hrDataTypeEle.setAttribute(XmlUtils.HR_ATTR_DATA_CATEGORY, dataCategoryName);
                hrDataTypeEle.setAttribute(XmlUtils.HR_ATTR_DATA_TYPE, dataTypeName);
                XmlUtils.maybeSetHrBoolAttr(
                        hrDataTypeEle,
                        XmlUtils.HR_ATTR_IS_COLLECTION_OPTIONAL,
                        dataType.getIsCollectionOptional());
                XmlUtils.maybeSetHrBoolAttr(
                        hrDataTypeEle,
                        XmlUtils.HR_ATTR_IS_SHARING_OPTIONAL,
                        dataType.getIsSharingOptional());
                XmlUtils.maybeSetHrBoolAttr(
                        hrDataTypeEle, XmlUtils.HR_ATTR_EPHEMERAL, dataType.getEphemeral());
                hrDataTypeEle.setAttribute(
                        XmlUtils.HR_ATTR_PURPOSES,
                        String.join(
                                "|",
                                dataType.getPurposes().stream()
                                        .map(DataType.Purpose::toString)
                                        .toList()));
                dataLabelsEle.appendChild(hrDataTypeEle);
            }
        }
    }
}
