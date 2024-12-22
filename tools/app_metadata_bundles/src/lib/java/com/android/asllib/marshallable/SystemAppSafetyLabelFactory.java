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

public class SystemAppSafetyLabelFactory implements AslMarshallableFactory<SystemAppSafetyLabel> {
    private final Map<Long, Set<String>> mRecognizedHrAttrs =
            Map.ofEntries(
                    Map.entry(1L, Set.of(XmlUtils.HR_ATTR_URL)),
                    Map.entry(2L, Set.of(XmlUtils.HR_ATTR_DECLARATION)));
    private final Map<Long, Set<String>> mRequiredHrAttrs =
            Map.ofEntries(
                    Map.entry(1L, Set.of(XmlUtils.HR_ATTR_URL)),
                    Map.entry(2L, Set.of(XmlUtils.HR_ATTR_DECLARATION)));
    private final Map<Long, Set<String>> mRecognizedHrEles = Map.ofEntries(Map.entry(1L, Set.of()));
    private final Map<Long, Set<String>> mRecognizedOdEleNames =
            Map.ofEntries(
                    Map.entry(1L, Set.of(XmlUtils.OD_NAME_URL)),
                    Map.entry(2L, Set.of(XmlUtils.OD_NAME_DECLARATION)));
    private final Map<Long, Set<String>> mRequiredOdEleNames =
            Map.ofEntries(
                    Map.entry(1L, Set.of(XmlUtils.OD_NAME_URL)),
                    Map.entry(2L, Set.of(XmlUtils.OD_NAME_DECLARATION)));

    /** Creates a {@link SystemAppSafetyLabel} from the human-readable DOM element. */
    public SystemAppSafetyLabel createFromHrElement(Element systemAppSafetyLabelEle, long version)
            throws MalformedXmlException {
        if (systemAppSafetyLabelEle == null) {
            return null;
        }
        XmlUtils.throwIfExtraneousAttributes(
                systemAppSafetyLabelEle,
                XmlUtils.getMostRecentVersion(mRecognizedHrAttrs, version));
        XmlUtils.throwIfExtraneousChildrenHr(
                systemAppSafetyLabelEle, XmlUtils.getMostRecentVersion(mRecognizedHrEles, version));

        String url =
                XmlUtils.getStringAttr(
                        systemAppSafetyLabelEle,
                        XmlUtils.HR_ATTR_URL,
                        XmlUtils.getMostRecentVersion(mRequiredHrAttrs, version));
        Boolean declaration =
                XmlUtils.getBoolAttr(
                        systemAppSafetyLabelEle,
                        XmlUtils.HR_ATTR_DECLARATION,
                        XmlUtils.getMostRecentVersion(mRequiredHrAttrs, version));
        return new SystemAppSafetyLabel(url, declaration);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    public SystemAppSafetyLabel createFromOdElement(Element systemAppSafetyLabelEle, long version)
            throws MalformedXmlException {
        if (systemAppSafetyLabelEle == null) {
            return null;
        }
        XmlUtils.throwIfExtraneousChildrenOd(
                systemAppSafetyLabelEle,
                XmlUtils.getMostRecentVersion(mRecognizedOdEleNames, version));
        String url =
                XmlUtils.getOdStringEle(
                        systemAppSafetyLabelEle,
                        XmlUtils.OD_NAME_URL,
                        XmlUtils.getMostRecentVersion(mRequiredOdEleNames, version));
        Boolean declaration =
                XmlUtils.getOdBoolEle(
                        systemAppSafetyLabelEle,
                        XmlUtils.OD_NAME_DECLARATION,
                        XmlUtils.getMostRecentVersion(mRequiredOdEleNames, version));
        return new SystemAppSafetyLabel(url, declaration);
    }
}
