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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DataTypeFactory implements AslMarshallableFactory<DataType> {
    /** Creates a {@link DataType} from the human-readable DOM element. */
    @Override
    public DataType createFromHrElements(List<Element> elements) {
        Element hrDataTypeEle = XmlUtils.getSingleElement(elements);
        String dataTypeName = hrDataTypeEle.getAttribute(XmlUtils.HR_ATTR_DATA_TYPE);
        Set<DataType.Purpose> purposeSet =
                Arrays.stream(hrDataTypeEle.getAttribute(XmlUtils.HR_ATTR_PURPOSES).split("\\|"))
                        .map(DataType.Purpose::forString)
                        .collect(Collectors.toUnmodifiableSet());
        Boolean isCollectionOptional =
                XmlUtils.fromString(
                        hrDataTypeEle.getAttribute(XmlUtils.HR_ATTR_IS_SHARING_OPTIONAL));
        Boolean isSharingOptional =
                XmlUtils.fromString(
                        hrDataTypeEle.getAttribute(XmlUtils.HR_ATTR_IS_COLLECTION_OPTIONAL));
        Boolean ephemeral =
                XmlUtils.fromString(hrDataTypeEle.getAttribute(XmlUtils.HR_ATTR_EPHEMERAL));
        return new DataType(
                dataTypeName, purposeSet, isCollectionOptional, isSharingOptional, ephemeral);
    }
}
