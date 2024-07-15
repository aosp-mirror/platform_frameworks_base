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
public class SystemAppSafetyLabel implements AslMarshallable {

    private final String mUrl;
    private final Boolean mDeclaration;

    public SystemAppSafetyLabel(String url, Boolean d) {
        this.mDeclaration = d;
        this.mUrl = null;
    }

    /** Creates an on-device DOM element from the {@link SystemAppSafetyLabel}. */
    public Element toOdDomElement(Document doc) {
        Element systemAppSafetyLabelEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_SYSTEM_APP_SAFETY_LABEL);
        if (mUrl != null) {
            systemAppSafetyLabelEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_URL, mUrl));
        }
        if (mDeclaration != null) {
            systemAppSafetyLabelEle.appendChild(
                    XmlUtils.createOdBooleanEle(doc, XmlUtils.OD_NAME_DECLARATION, mDeclaration));
        }
        return systemAppSafetyLabelEle;
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    public Element toHrDomElement(Document doc) {
        Element systemAppSafetyLabelEle =
                doc.createElement(XmlUtils.HR_TAG_SYSTEM_APP_SAFETY_LABEL);
        if (mUrl != null) {
            systemAppSafetyLabelEle.setAttribute(XmlUtils.HR_ATTR_URL, mUrl);
        }
        if (mDeclaration != null) {
            systemAppSafetyLabelEle.setAttribute(
                    XmlUtils.HR_ATTR_DECLARATION, String.valueOf(mDeclaration));
        }
        return systemAppSafetyLabelEle;
    }
}
