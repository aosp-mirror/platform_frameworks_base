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

import com.android.asllib.util.AslgenUtil;
import com.android.asllib.util.DataCategoryConstants;
import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Element;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DataLabelsFactory implements AslMarshallableFactory<DataLabels> {

    /** Creates a {@link DataLabels} from the human-readable DOM element. */
    @Override
    public DataLabels createFromHrElements(List<Element> elements) throws MalformedXmlException {
        Element ele = XmlUtils.getSingleElement(elements);
        if (ele == null) {
            AslgenUtil.logI("Found no DataLabels in hr format.");
            return null;
        }
        Map<String, DataCategory> dataAccessed =
                getDataCategoriesWithTag(ele, XmlUtils.HR_TAG_DATA_ACCESSED);
        Map<String, DataCategory> dataCollected =
                getDataCategoriesWithTag(ele, XmlUtils.HR_TAG_DATA_COLLECTED);
        Map<String, DataCategory> dataShared =
                getDataCategoriesWithTag(ele, XmlUtils.HR_TAG_DATA_SHARED);
        DataLabels dataLabels = new DataLabels(dataAccessed, dataCollected, dataShared);
        validateIsXOptional(dataLabels);
        return dataLabels;
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public DataLabels createFromOdElements(List<Element> elements) throws MalformedXmlException {
        Element dataLabelsEle = XmlUtils.getSingleElement(elements);
        if (dataLabelsEle == null) {
            AslgenUtil.logI("Found no DataLabels in od format.");
            return null;
        }
        Map<String, DataCategory> dataAccessed =
                getOdDataCategoriesWithTag(dataLabelsEle, XmlUtils.OD_NAME_DATA_ACCESSED);
        Map<String, DataCategory> dataCollected =
                getOdDataCategoriesWithTag(dataLabelsEle, XmlUtils.OD_NAME_DATA_COLLECTED);
        Map<String, DataCategory> dataShared =
                getOdDataCategoriesWithTag(dataLabelsEle, XmlUtils.OD_NAME_DATA_SHARED);
        DataLabels dataLabels = new DataLabels(dataAccessed, dataCollected, dataShared);
        validateIsXOptional(dataLabels);
        return dataLabels;
    }

    private static Map<String, DataCategory> getOdDataCategoriesWithTag(
            Element dataLabelsEle, String dataCategoryUsageTypeTag) throws MalformedXmlException {
        Map<String, DataCategory> dataCategoryMap = new LinkedHashMap<String, DataCategory>();
        Element dataUsageEle =
                XmlUtils.getOdPbundleWithName(dataLabelsEle, dataCategoryUsageTypeTag, false);
        if (dataUsageEle == null) {
            return dataCategoryMap;
        }
        List<Element> dataCategoryEles = XmlUtils.asElementList(dataUsageEle.getChildNodes());
        for (Element dataCategoryEle : dataCategoryEles) {
            String dataCategoryName = dataCategoryEle.getAttribute(XmlUtils.OD_ATTR_NAME);
            DataCategory dataCategory =
                    new DataCategoryFactory().createFromOdElements(List.of(dataCategoryEle));
            dataCategoryMap.put(dataCategoryName, dataCategory);
        }
        return dataCategoryMap;
    }

    private static Map<String, DataCategory> getDataCategoriesWithTag(
            Element dataLabelsEle, String dataCategoryUsageTypeTag) throws MalformedXmlException {
        List<Element> dataUsedElements =
                XmlUtils.getChildrenByTagName(dataLabelsEle, dataCategoryUsageTypeTag);
        Map<String, DataCategory> dataCategoryMap = new LinkedHashMap<String, DataCategory>();

        Set<String> dataCategoryNames = new HashSet<String>();
        for (int i = 0; i < dataUsedElements.size(); i++) {
            Element dataUsedEle = dataUsedElements.get(i);
            String dataCategoryName = dataUsedEle.getAttribute(XmlUtils.HR_ATTR_DATA_CATEGORY);
            if (!DataCategoryConstants.getValidDataCategories().contains(dataCategoryName)) {
                throw new MalformedXmlException(
                        String.format("Unrecognized category name: %s", dataCategoryName));
            }
            dataCategoryNames.add(dataCategoryName);
        }
        for (String dataCategoryName : dataCategoryNames) {
            var dataCategoryElements =
                    dataUsedElements.stream()
                            .filter(
                                    ele ->
                                            ele.getAttribute(XmlUtils.HR_ATTR_DATA_CATEGORY)
                                                    .equals(dataCategoryName))
                            .toList();
            DataCategory dataCategory =
                    new DataCategoryFactory().createFromHrElements(dataCategoryElements);
            dataCategoryMap.put(dataCategoryName, dataCategory);
        }
        return dataCategoryMap;
    }

    private void validateIsXOptional(DataLabels dataLabels) throws MalformedXmlException {
        // Validate booleans such as isCollectionOptional, isSharingOptional.
        for (DataCategory dataCategory : dataLabels.getDataAccessed().values()) {
            for (DataType dataType : dataCategory.getDataTypes().values()) {
                if (dataType.getIsSharingOptional() != null) {
                    throw new MalformedXmlException(
                            String.format(
                                    "isSharingOptional was unexpectedly defined on a DataType"
                                            + " belonging to data accessed: %s",
                                    dataType.getDataTypeName()));
                }
                if (dataType.getIsCollectionOptional() != null) {
                    throw new MalformedXmlException(
                            String.format(
                                    "isCollectionOptional was unexpectedly defined on a DataType"
                                            + " belonging to data accessed: %s",
                                    dataType.getDataTypeName()));
                }
            }
        }
        for (DataCategory dataCategory : dataLabels.getDataCollected().values()) {
            for (DataType dataType : dataCategory.getDataTypes().values()) {
                if (dataType.getIsSharingOptional() != null) {
                    throw new MalformedXmlException(
                            String.format(
                                    "isSharingOptional was unexpectedly defined on a DataType"
                                            + " belonging to data collected: %s",
                                    dataType.getDataTypeName()));
                }
            }
        }
        for (DataCategory dataCategory : dataLabels.getDataShared().values()) {
            for (DataType dataType : dataCategory.getDataTypes().values()) {
                if (dataType.getIsCollectionOptional() != null) {
                    throw new MalformedXmlException(
                            String.format(
                                    "isCollectionOptional was unexpectedly defined on a DataType"
                                            + " belonging to data shared: %s",
                                    dataType.getDataTypeName()));
                }
            }
        }
    }
}
