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

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data label representation with data shared and data collected maps containing zero or more {@link
 * DataCategory}
 */
public class DataLabels implements AslMarshallable {
    private final Map<String, DataCategory> mDataCollected;
    private final Map<String, DataCategory> mDataShared;

    public DataLabels(
            Map<String, DataCategory> dataCollected,
            Map<String, DataCategory> dataShared) {
        mDataCollected = dataCollected;
        mDataShared = dataShared;
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
    public Element toOdDomElement(Document doc) {
        Element dataLabelsEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_DATA_LABELS);

        maybeAppendDataUsages(doc, dataLabelsEle, mDataCollected, XmlUtils.OD_NAME_DATA_COLLECTED);
        maybeAppendDataUsages(doc, dataLabelsEle, mDataShared, XmlUtils.OD_NAME_DATA_SHARED);

        return dataLabelsEle;
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    public Element toHrDomElement(Document doc) {
        Element dataLabelsEle = doc.createElement(XmlUtils.HR_TAG_DATA_LABELS);
        maybeAppendHrDataUsages(
                doc, dataLabelsEle, mDataCollected, XmlUtils.HR_TAG_DATA_COLLECTED, false);
        maybeAppendHrDataUsages(
                doc, dataLabelsEle, mDataCollected, XmlUtils.HR_TAG_DATA_COLLECTED_EPHEMERAL, true);
        maybeAppendHrDataUsages(
                doc, dataLabelsEle, mDataShared, XmlUtils.HR_TAG_DATA_SHARED, false);
        return dataLabelsEle;
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
                dataCategoryEle.appendChild(dataType.toOdDomElement(doc));
            }
            dataUsageEle.appendChild(dataCategoryEle);
        }
        dataLabelsEle.appendChild(dataUsageEle);
    }

    private void maybeAppendHrDataUsages(
            Document doc,
            Element dataLabelsEle,
            Map<String, DataCategory> dataCategoriesMap,
            String dataUsageTypeName,
            boolean ephemeral) {
        if (dataCategoriesMap.isEmpty()) {
            return;
        }
        for (String dataCategoryName : dataCategoriesMap.keySet()) {
            DataCategory dataCategory = dataCategoriesMap.get(dataCategoryName);
            for (String dataTypeName : dataCategory.getDataTypes().keySet()) {
                DataType dataType = dataCategory.getDataTypes().get(dataTypeName);
                if (ephemeral
                        != (dataType.getEphemeral() != null ? dataType.getEphemeral() : false)) {
                    continue;
                }

                Element hrDataTypeEle = doc.createElement(dataUsageTypeName);
                hrDataTypeEle.setAttribute(
                        XmlUtils.HR_ATTR_DATA_TYPE,
                        dataCategoryName + XmlUtils.DATA_TYPE_SEPARATOR + dataTypeName);
                XmlUtils.maybeSetHrBoolAttr(
                        hrDataTypeEle,
                        XmlUtils.HR_ATTR_IS_COLLECTION_OPTIONAL,
                        dataType.getIsCollectionOptional());
                XmlUtils.maybeSetHrBoolAttr(
                        hrDataTypeEle,
                        XmlUtils.HR_ATTR_IS_SHARING_OPTIONAL,
                        dataType.getIsSharingOptional());
                hrDataTypeEle.setAttribute(
                        XmlUtils.HR_ATTR_PURPOSES,
                        String.join(
                                "|",
                                dataType.getPurposes().stream()
                                        .map(DataType.Purpose::toString)
                                        .collect(Collectors.toList())));
                dataLabelsEle.appendChild(hrDataTypeEle);
            }
        }
    }
}
