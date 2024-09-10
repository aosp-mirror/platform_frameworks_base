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

import java.util.Map;
import java.util.Set;

public class SafetyLabelsFactory implements AslMarshallableFactory<SafetyLabels> {
    private final Map<Long, Set<String>> mRecognizedHrAttrs =
            Map.ofEntries(Map.entry(1L, Set.of()));
    private final Map<Long, Set<String>> mRequiredHrAttrs = Map.ofEntries(Map.entry(1L, Set.of()));
    private final Map<Long, Set<String>> mRecognizedHrEles =
            Map.ofEntries(
                    Map.entry(
                            1L,
                            Set.of(
                                    XmlUtils.HR_TAG_DATA_LABELS,
                                    XmlUtils.HR_TAG_SECURITY_LABELS,
                                    XmlUtils.HR_TAG_THIRD_PARTY_VERIFICATION)),
                    Map.entry(2L, Set.of(XmlUtils.HR_TAG_DATA_LABELS)));
    private final Map<Long, Set<String>> mRequiredHrEles = Map.ofEntries(Map.entry(1L, Set.of()));
    private final Map<Long, Set<String>> mRecognizedOdEleNames =
            Map.ofEntries(
                    Map.entry(
                            1L,
                            Set.of(
                                    XmlUtils.OD_NAME_DATA_LABELS,
                                    XmlUtils.OD_NAME_SECURITY_LABELS,
                                    XmlUtils.OD_NAME_THIRD_PARTY_VERIFICATION)),
                    Map.entry(2L, Set.of(XmlUtils.OD_NAME_DATA_LABELS)));
    private final Map<Long, Set<String>> mRequiredOdEles = Map.ofEntries(Map.entry(1L, Set.of()));

    /** Creates a {@link SafetyLabels} from the human-readable DOM element. */
    public SafetyLabels createFromHrElement(Element safetyLabelsEle, long version)
            throws MalformedXmlException {
        if (safetyLabelsEle == null) {
            return null;
        }

        XmlUtils.throwIfExtraneousAttributes(
                safetyLabelsEle, XmlUtils.getMostRecentVersion(mRecognizedHrAttrs, version));
        XmlUtils.throwIfExtraneousChildrenHr(
                safetyLabelsEle, XmlUtils.getMostRecentVersion(mRecognizedHrEles, version));

        var requiredHrEles = XmlUtils.getMostRecentVersion(mRequiredHrEles, version);

        DataLabels dataLabels =
                new DataLabelsFactory()
                        .createFromHrElement(
                                XmlUtils.getSingleChildElement(
                                        safetyLabelsEle,
                                        XmlUtils.HR_TAG_DATA_LABELS,
                                        requiredHrEles));
        SecurityLabels securityLabels =
                new SecurityLabelsFactory()
                        .createFromHrElement(
                                XmlUtils.getSingleChildElement(
                                        safetyLabelsEle,
                                        XmlUtils.HR_TAG_SECURITY_LABELS,
                                        requiredHrEles));
        ThirdPartyVerification thirdPartyVerification =
                new ThirdPartyVerificationFactory()
                        .createFromHrElement(
                                XmlUtils.getSingleChildElement(
                                        safetyLabelsEle,
                                        XmlUtils.HR_TAG_THIRD_PARTY_VERIFICATION,
                                        requiredHrEles));
        return new SafetyLabels(dataLabels, securityLabels, thirdPartyVerification);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    public SafetyLabels createFromOdElement(Element safetyLabelsEle, long version)
            throws MalformedXmlException {
        if (safetyLabelsEle == null) {
            return null;
        }

        XmlUtils.throwIfExtraneousChildrenOd(
                safetyLabelsEle, XmlUtils.getMostRecentVersion(mRecognizedOdEleNames, version));
        var requiredOdEles = XmlUtils.getMostRecentVersion(mRequiredOdEles, version);

        DataLabels dataLabels =
                new DataLabelsFactory()
                        .createFromOdElement(
                                XmlUtils.getOdPbundleWithName(
                                        safetyLabelsEle,
                                        XmlUtils.OD_NAME_DATA_LABELS,
                                        requiredOdEles));
        SecurityLabels securityLabels =
                new SecurityLabelsFactory()
                        .createFromOdElement(
                                XmlUtils.getOdPbundleWithName(
                                        safetyLabelsEle,
                                        XmlUtils.OD_NAME_SECURITY_LABELS,
                                        requiredOdEles));
        ThirdPartyVerification thirdPartyVerification =
                new ThirdPartyVerificationFactory()
                        .createFromOdElement(
                                XmlUtils.getOdPbundleWithName(
                                        safetyLabelsEle,
                                        XmlUtils.OD_NAME_THIRD_PARTY_VERIFICATION,
                                        requiredOdEles));
        return new SafetyLabels(dataLabels, securityLabels, thirdPartyVerification);
    }
}
