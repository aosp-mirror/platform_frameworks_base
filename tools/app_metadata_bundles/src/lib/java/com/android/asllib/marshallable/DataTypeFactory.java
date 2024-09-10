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

import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Element;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class DataTypeFactory {
    /** Creates a {@link DataType} from the human-readable DOM element. */
    public DataType createFromHrElements(Element hrDataTypeEle, Boolean ephemeral)
            throws MalformedXmlException {
        String dataCategoryAndTypeCombinedStr =
                hrDataTypeEle.getAttribute(XmlUtils.HR_ATTR_DATA_TYPE);
        String[] strs = dataCategoryAndTypeCombinedStr.split(XmlUtils.DATA_TYPE_SEPARATOR);
        if (strs.length != 2) {
            throw new MalformedXmlException(
                    String.format(
                            "Could not parse human-readable data type string (expecting substring"
                                    + " of _data_type_): %s",
                            dataCategoryAndTypeCombinedStr));
        }
        String dataTypeName = strs[1];

        List<DataType.Purpose> purposes =
                XmlUtils.getPipelineSplitAttr(hrDataTypeEle, XmlUtils.HR_ATTR_PURPOSES, true)
                        .stream()
                        .map(DataType.Purpose::forString)
                        .collect(Collectors.toList());
        if (purposes.isEmpty()) {
            throw new MalformedXmlException(String.format("Found no purpose in: %s", dataTypeName));
        }
        if (new HashSet<>(purposes).size() != purposes.size()) {
            throw new MalformedXmlException(
                    String.format("Found non-unique purposes in: %s", dataTypeName));
        }
        Boolean isCollectionOptional =
                XmlUtils.getBoolAttr(hrDataTypeEle, XmlUtils.HR_ATTR_IS_COLLECTION_OPTIONAL, false);
        Boolean isSharingOptional =
                XmlUtils.getBoolAttr(hrDataTypeEle, XmlUtils.HR_ATTR_IS_SHARING_OPTIONAL, false);
        // Boolean ephemeral = XmlUtils.getBoolAttr(hrDataTypeEle, XmlUtils.HR_ATTR_EPHEMERAL,
        // false);
        return new DataType(
                dataTypeName, purposes, isCollectionOptional, isSharingOptional, ephemeral);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    public DataType createFromOdElement(Element odDataTypeEle) throws MalformedXmlException {
        String dataTypeName = odDataTypeEle.getAttribute(XmlUtils.OD_ATTR_NAME);
        List<Integer> purposeInts =
                XmlUtils.getOdIntArray(odDataTypeEle, XmlUtils.OD_NAME_PURPOSES, true);
        List<DataType.Purpose> purposes =
                purposeInts.stream().map(DataType.Purpose::forValue).collect(Collectors.toList());
        if (purposes.isEmpty()) {
            throw new MalformedXmlException(String.format("Found no purpose in: %s", dataTypeName));
        }
        if (new HashSet<>(purposes).size() != purposes.size()) {
            throw new MalformedXmlException(
                    String.format("Found non-unique purposes in: %s", dataTypeName));
        }
        Boolean isCollectionOptional =
                XmlUtils.getOdBoolEle(
                        odDataTypeEle, XmlUtils.OD_NAME_IS_COLLECTION_OPTIONAL, false);
        Boolean isSharingOptional =
                XmlUtils.getOdBoolEle(odDataTypeEle, XmlUtils.OD_NAME_IS_SHARING_OPTIONAL, false);
        Boolean ephemeral = XmlUtils.getOdBoolEle(odDataTypeEle, XmlUtils.OD_NAME_EPHEMERAL, false);

        return new DataType(
                dataTypeName, purposes, isCollectionOptional, isSharingOptional, ephemeral);
    }
}
