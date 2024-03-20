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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

/**
 * Data label representation with data shared and data collected maps containing zero or more {@link
 * DataCategory}
 */
public class DataLabels {
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
     * Returns the data accessed {@link Map} of {@link com.android.asllib.DataCategoryConstants} to
     * {@link DataCategory}
     */
    public Map<String, DataCategory> getDataAccessed() {
        return mDataAccessed;
    }

    /**
     * Returns the data collected {@link Map} of {@link com.android.asllib.DataCategoryConstants} to
     * {@link DataCategory}
     */
    public Map<String, DataCategory> getDataCollected() {
        return mDataCollected;
    }

    /**
     * Returns the data shared {@link Map} of {@link com.android.asllib.DataCategoryConstants} to
     * {@link DataCategory}
     */
    public Map<String, DataCategory> getDataShared() {
        return mDataShared;
    }

    /** Creates a {@link DataLabels} from the human-readable DOM element. */
    public static DataLabels createFromHrElement(Element ele) {
        Map<String, DataCategory> dataAccessed =
                getDataCategoriesWithTag(ele, XmlUtils.HR_TAG_DATA_ACCESSED);
        Map<String, DataCategory> dataCollected =
                getDataCategoriesWithTag(ele, XmlUtils.HR_TAG_DATA_COLLECTED);
        Map<String, DataCategory> dataShared =
                getDataCategoriesWithTag(ele, XmlUtils.HR_TAG_DATA_SHARED);
        return new DataLabels(dataAccessed, dataCollected, dataShared);
    }

    private static Map<String, DataCategory> getDataCategoriesWithTag(
            Element dataLabelsEle, String dataCategoryUsageTypeTag) {
        Map<String, Map<String, DataType>> dataTypeMap =
                new HashMap<String, Map<String, DataType>>();
        NodeList dataSharedNodeList = dataLabelsEle.getElementsByTagName(dataCategoryUsageTypeTag);

        for (int i = 0; i < dataSharedNodeList.getLength(); i++) {
            Element dataSharedEle = (Element) dataSharedNodeList.item(i);
            String dataCategoryName = dataSharedEle.getAttribute(XmlUtils.HR_ATTR_DATA_CATEGORY);
            String dataTypeName = dataSharedEle.getAttribute(XmlUtils.HR_ATTR_DATA_TYPE);

            if (!dataTypeMap.containsKey((dataCategoryName))) {
                dataTypeMap.put(dataCategoryName, new HashMap<String, DataType>());
            }
            dataTypeMap
                    .get(dataCategoryName)
                    .put(dataTypeName, DataType.createFromHrElement(dataSharedEle));
        }

        Map<String, DataCategory> dataCategoryMap = new HashMap<String, DataCategory>();
        for (String dataCategoryName : dataTypeMap.keySet()) {
            Map<String, DataType> dataTypes = dataTypeMap.get(dataCategoryName);
            dataCategoryMap.put(dataCategoryName, DataCategory.create(dataTypes));
        }
        return dataCategoryMap;
    }

    /** Gets the on-device DOM element for the {@link DataLabels}. */
    public Element toOdDomElement(Document doc) {
        Element dataLabelsEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_DATA_LABELS);

        maybeAppendDataUsages(doc, dataLabelsEle, mDataCollected, XmlUtils.OD_NAME_DATA_ACCESSED);
        maybeAppendDataUsages(doc, dataLabelsEle, mDataCollected, XmlUtils.OD_NAME_DATA_COLLECTED);
        maybeAppendDataUsages(doc, dataLabelsEle, mDataShared, XmlUtils.OD_NAME_DATA_SHARED);

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
                Element dataTypeEle = XmlUtils.createPbundleEleWithName(doc, dataTypeName);
                if (!dataType.getPurposeSet().isEmpty()) {
                    Element purposesEle = doc.createElement(XmlUtils.OD_TAG_INT_ARRAY);
                    purposesEle.setAttribute(XmlUtils.OD_ATTR_NAME, XmlUtils.OD_NAME_PURPOSES);
                    purposesEle.setAttribute(
                            XmlUtils.OD_ATTR_NUM, String.valueOf(dataType.getPurposeSet().size()));
                    for (DataType.Purpose purpose : dataType.getPurposeSet()) {
                        Element purposeEle = doc.createElement(XmlUtils.OD_TAG_ITEM);
                        purposeEle.setAttribute(
                                XmlUtils.OD_ATTR_VALUE, String.valueOf(purpose.getValue()));
                        purposesEle.appendChild(purposeEle);
                    }
                    dataTypeEle.appendChild(purposesEle);
                }

                maybeAddBoolToOdElement(
                        doc,
                        dataTypeEle,
                        dataType.getIsCollectionOptional(),
                        XmlUtils.OD_NAME_IS_COLLECTION_OPTIONAL);
                maybeAddBoolToOdElement(
                        doc,
                        dataTypeEle,
                        dataType.getIsSharingOptional(),
                        XmlUtils.OD_NAME_IS_SHARING_OPTIONAL);
                maybeAddBoolToOdElement(
                        doc, dataTypeEle, dataType.getEphemeral(), XmlUtils.OD_NAME_EPHEMERAL);

                dataCategoryEle.appendChild(dataTypeEle);
            }
            dataUsageEle.appendChild(dataCategoryEle);
        }
        dataLabelsEle.appendChild(dataUsageEle);
    }

    private static void maybeAddBoolToOdElement(
            Document doc, Element parentEle, Boolean b, String odName) {
        if (b == null) {
            return;
        }
        Element ele = XmlUtils.createOdBooleanEle(doc, odName, b);
        parentEle.appendChild(ele);
    }
}
