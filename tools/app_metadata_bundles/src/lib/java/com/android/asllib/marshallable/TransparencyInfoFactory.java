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

public class TransparencyInfoFactory implements AslMarshallableFactory<TransparencyInfo> {
    private final Map<Long, Set<String>> mRecognizedHrAttrs =
            Map.ofEntries(Map.entry(1L, Set.of()));
    private final Map<Long, Set<String>> mRequiredHrAttrs = Map.ofEntries(Map.entry(1L, Set.of()));
    private final Map<Long, Set<String>> mRecognizedHrEles =
            Map.ofEntries(
                    Map.entry(1L, Set.of(XmlUtils.HR_TAG_DEVELOPER_INFO, XmlUtils.HR_TAG_APP_INFO)),
                    Map.entry(2L, Set.of(XmlUtils.HR_TAG_APP_INFO)));
    private final Map<Long, Set<String>> mRequiredHrEles =
            Map.ofEntries(Map.entry(1L, Set.of()), Map.entry(2L, Set.of(XmlUtils.HR_TAG_APP_INFO)));
    private final Map<Long, Set<String>> mRecognizedOdEleNames =
            Map.ofEntries(
                    Map.entry(
                            1L, Set.of(XmlUtils.OD_NAME_DEVELOPER_INFO, XmlUtils.OD_NAME_APP_INFO)),
                    Map.entry(2L, Set.of(XmlUtils.OD_NAME_APP_INFO)));
    private final Map<Long, Set<String>> mRequiredOdEles =
            Map.ofEntries(
                    Map.entry(1L, Set.of()), Map.entry(2L, Set.of(XmlUtils.OD_NAME_APP_INFO)));

    /** Creates a {@link TransparencyInfo} from the human-readable DOM element. */
    public TransparencyInfo createFromHrElement(Element transparencyInfoEle, long version)
            throws MalformedXmlException {
        if (transparencyInfoEle == null) {
            return null;
        }
        XmlUtils.throwIfExtraneousAttributes(
                transparencyInfoEle, XmlUtils.getMostRecentVersion(mRecognizedHrAttrs, version));
        XmlUtils.throwIfExtraneousChildrenHr(
                transparencyInfoEle, XmlUtils.getMostRecentVersion(mRecognizedHrEles, version));

        Element developerInfoEle =
                XmlUtils.getSingleChildElement(
                        transparencyInfoEle,
                        XmlUtils.HR_TAG_DEVELOPER_INFO,
                        XmlUtils.getMostRecentVersion(mRequiredHrEles, version));
        DeveloperInfo developerInfo =
                new DeveloperInfoFactory().createFromHrElement(developerInfoEle);
        Element appInfoEle =
                XmlUtils.getSingleChildElement(
                        transparencyInfoEle,
                        XmlUtils.HR_TAG_APP_INFO,
                        XmlUtils.getMostRecentVersion(mRequiredHrEles, version));
        AppInfo appInfo = new AppInfoFactory().createFromHrElement(appInfoEle, version);

        return new TransparencyInfo(developerInfo, appInfo);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    public TransparencyInfo createFromOdElement(Element transparencyInfoEle, long version)
            throws MalformedXmlException {
        if (transparencyInfoEle == null) {
            return null;
        }
        XmlUtils.throwIfExtraneousChildrenOd(
                transparencyInfoEle, XmlUtils.getMostRecentVersion(mRecognizedOdEleNames, version));

        Element developerInfoEle =
                XmlUtils.getOdPbundleWithName(
                        transparencyInfoEle,
                        XmlUtils.OD_NAME_DEVELOPER_INFO,
                        XmlUtils.getMostRecentVersion(mRequiredOdEles, version));
        DeveloperInfo developerInfo =
                new DeveloperInfoFactory().createFromOdElement(developerInfoEle);
        Element appInfoEle =
                XmlUtils.getOdPbundleWithName(
                        transparencyInfoEle,
                        XmlUtils.OD_NAME_APP_INFO,
                        XmlUtils.getMostRecentVersion(mRequiredOdEles, version));
        AppInfo appInfo = new AppInfoFactory().createFromOdElement(appInfoEle, version);

        return new TransparencyInfo(developerInfo, appInfo);
    }
}
