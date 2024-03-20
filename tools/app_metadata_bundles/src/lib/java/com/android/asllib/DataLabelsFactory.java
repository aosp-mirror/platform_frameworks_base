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

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DataLabelsFactory implements AslMarshallableFactory<DataLabels> {

    /** Creates a {@link DataLabels} from the human-readable DOM element. */
    @Override
    public DataLabels createFromHrElements(List<Element> elements) {
        Element ele = XmlUtils.getSingleElement(elements);
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
        NodeList dataUsedNodeList = dataLabelsEle.getElementsByTagName(dataCategoryUsageTypeTag);
        Map<String, DataCategory> dataCategoryMap = new HashMap<String, DataCategory>();

        Set<String> dataCategoryNames = new HashSet<String>();
        for (int i = 0; i < dataUsedNodeList.getLength(); i++) {
            Element dataUsedEle = (Element) dataUsedNodeList.item(i);
            String dataCategoryName = dataUsedEle.getAttribute(XmlUtils.HR_ATTR_DATA_CATEGORY);
            dataCategoryNames.add(dataCategoryName);
        }
        for (String dataCategoryName : dataCategoryNames) {
            var dataCategoryElements =
                    XmlUtils.asElementList(dataUsedNodeList).stream()
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
}
