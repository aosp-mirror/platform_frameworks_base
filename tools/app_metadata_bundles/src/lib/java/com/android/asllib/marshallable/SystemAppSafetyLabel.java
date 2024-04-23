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

import java.util.List;

/** Safety Label representation containing zero or more {@link DataCategory} for data shared */
public class SystemAppSafetyLabel implements AslMarshallable {

    private final String mUrl;

    public SystemAppSafetyLabel(String url) {
        this.mUrl = url;
    }

    /** Returns the system app safety label URL. */
    public String getUrl() {
        return mUrl;
    }

    /** Creates an on-device DOM element from the {@link SystemAppSafetyLabel}. */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element systemAppSafetyLabelEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_SYSTEM_APP_SAFETY_LABEL);
        systemAppSafetyLabelEle.appendChild(
                XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_URL, mUrl));
        return XmlUtils.listOf(systemAppSafetyLabelEle);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        Element systemAppSafetyLabelEle =
                doc.createElement(XmlUtils.HR_TAG_SYSTEM_APP_SAFETY_LABEL);
        systemAppSafetyLabelEle.setAttribute(XmlUtils.HR_ATTR_URL, mUrl);
        return XmlUtils.listOf(systemAppSafetyLabelEle);
    }
}
