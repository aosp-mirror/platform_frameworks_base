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

public class AndroidSafetyLabelFactory implements AslMarshallableFactory<AndroidSafetyLabel> {
    private final Map<Long, Set<String>> mRecognizedHrAttrs =
            Map.ofEntries(Map.entry(1L, Set.of(XmlUtils.HR_ATTR_VERSION)));
    private final Map<Long, Set<String>> mRequiredHrAttrs =
            Map.ofEntries(Map.entry(1L, Set.of(XmlUtils.HR_ATTR_VERSION)));
    private final Map<Long, Set<String>> mRecognizedHrEles =
            Map.ofEntries(
                    Map.entry(
                            1L,
                            Set.of(
                                    XmlUtils.HR_TAG_SYSTEM_APP_SAFETY_LABEL,
                                    XmlUtils.HR_TAG_SAFETY_LABELS,
                                    XmlUtils.HR_TAG_TRANSPARENCY_INFO)));
    private final Map<Long, Set<String>> mRequiredHrEles =
            Map.ofEntries(
                    Map.entry(1L, Set.of()),
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.HR_TAG_SYSTEM_APP_SAFETY_LABEL,
                                    XmlUtils.HR_TAG_TRANSPARENCY_INFO)));
    private final Map<Long, Set<String>> mRecognizedOdEleNames =
            Map.ofEntries(
                    Map.entry(
                            1L,
                            Set.of(
                                    XmlUtils.OD_NAME_VERSION,
                                    XmlUtils.OD_NAME_SAFETY_LABELS,
                                    XmlUtils.OD_NAME_SYSTEM_APP_SAFETY_LABEL,
                                    XmlUtils.OD_NAME_TRANSPARENCY_INFO)));
    private final Map<Long, Set<String>> mRequiredOdEleNames =
            Map.ofEntries(
                    Map.entry(1L, Set.of(XmlUtils.OD_NAME_VERSION)),
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.OD_NAME_VERSION,
                                    XmlUtils.OD_NAME_SYSTEM_APP_SAFETY_LABEL,
                                    XmlUtils.OD_NAME_TRANSPARENCY_INFO)));

    /** Creates an {@link AndroidSafetyLabel} from human-readable DOM element */
    public AndroidSafetyLabel createFromHrElement(Element appMetadataBundlesEle)
            throws MalformedXmlException {
        long version = XmlUtils.tryGetVersion(appMetadataBundlesEle);
        XmlUtils.throwIfExtraneousAttributes(
                appMetadataBundlesEle, XmlUtils.getMostRecentVersion(mRecognizedHrAttrs, version));
        XmlUtils.throwIfExtraneousChildrenHr(
                appMetadataBundlesEle, XmlUtils.getMostRecentVersion(mRecognizedHrEles, version));

        Element safetyLabelsEle =
                XmlUtils.getSingleChildElement(
                        appMetadataBundlesEle,
                        XmlUtils.HR_TAG_SAFETY_LABELS,
                        XmlUtils.getMostRecentVersion(mRequiredHrEles, version));
        SafetyLabels safetyLabels =
                new SafetyLabelsFactory().createFromHrElement(safetyLabelsEle, version);

        Element systemAppSafetyLabelEle =
                XmlUtils.getSingleChildElement(
                        appMetadataBundlesEle,
                        XmlUtils.HR_TAG_SYSTEM_APP_SAFETY_LABEL,
                        XmlUtils.getMostRecentVersion(mRequiredHrEles, version));
        SystemAppSafetyLabel systemAppSafetyLabel =
                new SystemAppSafetyLabelFactory()
                        .createFromHrElement(systemAppSafetyLabelEle, version);

        Element transparencyInfoEle =
                XmlUtils.getSingleChildElement(
                        appMetadataBundlesEle,
                        XmlUtils.HR_TAG_TRANSPARENCY_INFO,
                        XmlUtils.getMostRecentVersion(mRequiredHrEles, version));
        TransparencyInfo transparencyInfo =
                new TransparencyInfoFactory().createFromHrElement(transparencyInfoEle, version);

        return new AndroidSafetyLabel(
                version, systemAppSafetyLabel, safetyLabels, transparencyInfo);
    }

    /** Creates an {@link AndroidSafetyLabel} from on-device DOM elements */
    public AndroidSafetyLabel createFromOdElement(Element bundleEle) throws MalformedXmlException {
        Long version = XmlUtils.getOdLongEle(bundleEle, XmlUtils.OD_NAME_VERSION, true);
        XmlUtils.throwIfExtraneousChildrenOd(
                bundleEle, XmlUtils.getMostRecentVersion(mRecognizedOdEleNames, version));

        Element safetyLabelsEle =
                XmlUtils.getOdPbundleWithName(
                        bundleEle,
                        XmlUtils.OD_NAME_SAFETY_LABELS,
                        XmlUtils.getMostRecentVersion(mRequiredOdEleNames, version));
        SafetyLabels safetyLabels =
                new SafetyLabelsFactory().createFromOdElement(safetyLabelsEle, version);

        Element systemAppSafetyLabelEle =
                XmlUtils.getOdPbundleWithName(
                        bundleEle,
                        XmlUtils.OD_NAME_SYSTEM_APP_SAFETY_LABEL,
                        XmlUtils.getMostRecentVersion(mRequiredOdEleNames, version));
        SystemAppSafetyLabel systemAppSafetyLabel =
                new SystemAppSafetyLabelFactory()
                        .createFromOdElement(systemAppSafetyLabelEle, version);

        Element transparencyInfoEle =
                XmlUtils.getOdPbundleWithName(
                        bundleEle,
                        XmlUtils.OD_NAME_TRANSPARENCY_INFO,
                        XmlUtils.getMostRecentVersion(mRequiredOdEleNames, version));
        TransparencyInfo transparencyInfo =
                new TransparencyInfoFactory().createFromOdElement(transparencyInfoEle, version);

        return new AndroidSafetyLabel(
                version, systemAppSafetyLabel, safetyLabels, transparencyInfo);
    }
}
