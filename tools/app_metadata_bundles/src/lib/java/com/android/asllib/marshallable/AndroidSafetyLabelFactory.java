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

import java.util.List;

public class AndroidSafetyLabelFactory implements AslMarshallableFactory<AndroidSafetyLabel> {

    /** Creates an {@link AndroidSafetyLabel} from human-readable DOM element */
    @Override
    public AndroidSafetyLabel createFromHrElements(List<Element> appMetadataBundles)
            throws MalformedXmlException {
        Element appMetadataBundlesEle = XmlUtils.getSingleElement(appMetadataBundles);
        long version = XmlUtils.tryGetVersion(appMetadataBundlesEle);

        Element safetyLabelsEle =
                XmlUtils.getSingleChildElement(
                        appMetadataBundlesEle, XmlUtils.HR_TAG_SAFETY_LABELS, false);
        SafetyLabels safetyLabels =
                new SafetyLabelsFactory().createFromHrElements(XmlUtils.listOf(safetyLabelsEle));

        Element systemAppSafetyLabelEle =
                XmlUtils.getSingleChildElement(
                        appMetadataBundlesEle, XmlUtils.HR_TAG_SYSTEM_APP_SAFETY_LABEL, false);
        SystemAppSafetyLabel systemAppSafetyLabel =
                new SystemAppSafetyLabelFactory()
                        .createFromHrElements(XmlUtils.listOf(systemAppSafetyLabelEle));

        Element transparencyInfoEle =
                XmlUtils.getSingleChildElement(
                        appMetadataBundlesEle, XmlUtils.HR_TAG_TRANSPARENCY_INFO, false);
        TransparencyInfo transparencyInfo =
                new TransparencyInfoFactory()
                        .createFromHrElements(XmlUtils.listOf(transparencyInfoEle));

        return new AndroidSafetyLabel(
                version, systemAppSafetyLabel, safetyLabels, transparencyInfo);
    }

    /** Creates an {@link AndroidSafetyLabel} from on-device DOM elements */
    @Override
    public AndroidSafetyLabel createFromOdElements(List<Element> elements)
            throws MalformedXmlException {
        Element bundleEle = XmlUtils.getSingleElement(elements);
        Long version = XmlUtils.getOdLongEle(bundleEle, XmlUtils.OD_NAME_VERSION, true);

        Element safetyLabelsEle =
                XmlUtils.getOdPbundleWithName(bundleEle, XmlUtils.OD_NAME_SAFETY_LABELS, false);
        SafetyLabels safetyLabels =
                new SafetyLabelsFactory().createFromOdElements(XmlUtils.listOf(safetyLabelsEle));

        Element systemAppSafetyLabelEle =
                XmlUtils.getOdPbundleWithName(
                        bundleEle, XmlUtils.OD_NAME_SYSTEM_APP_SAFETY_LABEL, false);
        SystemAppSafetyLabel systemAppSafetyLabel =
                new SystemAppSafetyLabelFactory()
                        .createFromOdElements(XmlUtils.listOf(systemAppSafetyLabelEle));

        Element transparencyInfoEle =
                XmlUtils.getOdPbundleWithName(bundleEle, XmlUtils.OD_NAME_TRANSPARENCY_INFO, false);
        TransparencyInfo transparencyInfo =
                new TransparencyInfoFactory()
                        .createFromOdElements(XmlUtils.listOf(transparencyInfoEle));

        return new AndroidSafetyLabel(
                version, systemAppSafetyLabel, safetyLabels, transparencyInfo);
    }
}
