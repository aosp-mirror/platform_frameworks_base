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

/** Safety Label representation containing zero or more {@link DataCategory} for data shared */
public class SafetyLabels {

    private final Long mVersion;
    private final DataLabels mDataLabels;

    private SafetyLabels(Long version, DataLabels dataLabels) {
        this.mVersion = version;
        this.mDataLabels = dataLabels;
    }

    /** Returns the data label for the safety label */
    public DataLabels getDataLabel() {
        return mDataLabels;
    }

    /** Gets the version of the {@link SafetyLabels}. */
    public Long getVersion() {
        return mVersion;
    }

    /** Creates a {@link SafetyLabels} from the human-readable DOM element. */
    public static SafetyLabels createFromHrElement(Element safetyLabelsEle) {
        Long version;
        try {
            version = Long.parseLong(safetyLabelsEle.getAttribute(XmlUtils.HR_ATTR_VERSION));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Malformed or missing required version in safety labels.");
        }
        Element dataLabelsEle =
                XmlUtils.getSingleElement(safetyLabelsEle, XmlUtils.HR_TAG_DATA_LABELS);
        DataLabels dataLabels = DataLabels.createFromHrElement(dataLabelsEle);
        return new SafetyLabels(version, dataLabels);
    }

    /** Creates an on-device DOM element from the {@link SafetyLabels}. */
    public Element toOdDomElement(Document doc) {
        Element safetyLabelsEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_SAFETY_LABELS);
        safetyLabelsEle.appendChild(mDataLabels.toOdDomElement(doc));
        return safetyLabelsEle;
    }
}
