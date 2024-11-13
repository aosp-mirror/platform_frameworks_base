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

import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Safety Label representation containing zero or more {@link DataCategory} for data shared */
public class SafetyLabels implements AslMarshallable {
    private final DataLabels mDataLabels;
    private final SecurityLabels mSecurityLabels;
    private final ThirdPartyVerification mThirdPartyVerification;

    public SafetyLabels(
            DataLabels dataLabels,
            SecurityLabels securityLabels,
            ThirdPartyVerification thirdPartyVerification) {
        this.mDataLabels = dataLabels;
        this.mSecurityLabels = securityLabels;
        this.mThirdPartyVerification = thirdPartyVerification;
    }

    /** Creates an on-device DOM element from the {@link SafetyLabels}. */
    public Element toOdDomElement(Document doc) {
        Element safetyLabelsEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_SAFETY_LABELS);
        if (mDataLabels != null) {
            safetyLabelsEle.appendChild(mDataLabels.toOdDomElement(doc));
        }
        if (mSecurityLabels != null) {
            safetyLabelsEle.appendChild(mSecurityLabels.toOdDomElement(doc));
        }
        if (mThirdPartyVerification != null) {
            safetyLabelsEle.appendChild(mThirdPartyVerification.toOdDomElement(doc));
        }
        return safetyLabelsEle;
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    public Element toHrDomElement(Document doc) {
        Element safetyLabelsEle = doc.createElement(XmlUtils.HR_TAG_SAFETY_LABELS);

        if (mDataLabels != null) {
            safetyLabelsEle.appendChild(mDataLabels.toHrDomElement(doc));
        }
        if (mSecurityLabels != null) {
            safetyLabelsEle.appendChild(mSecurityLabels.toHrDomElement(doc));
        }
        if (mThirdPartyVerification != null) {
            safetyLabelsEle.appendChild(mThirdPartyVerification.toHrDomElement(doc));
        }
        return safetyLabelsEle;
    }
}
