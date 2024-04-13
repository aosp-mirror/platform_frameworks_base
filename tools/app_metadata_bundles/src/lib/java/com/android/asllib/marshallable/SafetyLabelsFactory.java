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
import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Element;

import java.util.List;

public class SafetyLabelsFactory implements AslMarshallableFactory<SafetyLabels> {

    /** Creates a {@link SafetyLabels} from the human-readable DOM element. */
    @Override
    public SafetyLabels createFromHrElements(List<Element> elements) throws MalformedXmlException {
        Element safetyLabelsEle = XmlUtils.getSingleElement(elements);
        if (safetyLabelsEle == null) {
            AslgenUtil.logI("No SafetyLabels found in hr format.");
            return null;
        }
        long version = XmlUtils.tryGetVersion(safetyLabelsEle);

        DataLabels dataLabels =
                new DataLabelsFactory()
                        .createFromHrElements(
                                XmlUtils.listOf(
                                        XmlUtils.getSingleChildElement(
                                                safetyLabelsEle,
                                                XmlUtils.HR_TAG_DATA_LABELS,
                                                false)));
        SecurityLabels securityLabels =
                new SecurityLabelsFactory()
                        .createFromHrElements(
                                XmlUtils.listOf(
                                        XmlUtils.getSingleChildElement(
                                                safetyLabelsEle,
                                                XmlUtils.HR_TAG_SECURITY_LABELS,
                                                false)));
        ThirdPartyVerification thirdPartyVerification =
                new ThirdPartyVerificationFactory()
                        .createFromHrElements(
                                XmlUtils.listOf(
                                        XmlUtils.getSingleChildElement(
                                                safetyLabelsEle,
                                                XmlUtils.HR_TAG_THIRD_PARTY_VERIFICATION,
                                                false)));
        return new SafetyLabels(version, dataLabels, securityLabels, thirdPartyVerification);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public SafetyLabels createFromOdElements(List<Element> elements) throws MalformedXmlException {
        Element safetyLabelsEle = XmlUtils.getSingleElement(elements);
        if (safetyLabelsEle == null) {
            AslgenUtil.logI("No SafetyLabels found in od format.");
            return null;
        }
        Long version = XmlUtils.getOdLongEle(safetyLabelsEle, XmlUtils.OD_NAME_VERSION, true);

        DataLabels dataLabels =
                new DataLabelsFactory()
                        .createFromOdElements(
                                XmlUtils.listOf(
                                        XmlUtils.getOdPbundleWithName(
                                                safetyLabelsEle,
                                                XmlUtils.OD_NAME_DATA_LABELS,
                                                false)));
        SecurityLabels securityLabels =
                new SecurityLabelsFactory()
                        .createFromOdElements(
                                XmlUtils.listOf(
                                        XmlUtils.getOdPbundleWithName(
                                                safetyLabelsEle,
                                                XmlUtils.OD_NAME_SECURITY_LABELS,
                                                false)));
        ThirdPartyVerification thirdPartyVerification =
                new ThirdPartyVerificationFactory()
                        .createFromOdElements(
                                XmlUtils.listOf(
                                        XmlUtils.getOdPbundleWithName(
                                                safetyLabelsEle,
                                                XmlUtils.OD_NAME_THIRD_PARTY_VERIFICATION,
                                                false)));
        return new SafetyLabels(version, dataLabels, securityLabels, thirdPartyVerification);
    }
}
