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

/** ThirdPartyVerification representation. */
public class ThirdPartyVerification implements AslMarshallable {

    private final String mUrl;

    public ThirdPartyVerification(String url) {
        this.mUrl = url;
    }

    /** Creates an on-device DOM element from the {@link ThirdPartyVerification}. */
    public Element toOdDomElement(Document doc) {
        Element ele =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_THIRD_PARTY_VERIFICATION);
        ele.appendChild(XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_URL, mUrl));
        return ele;
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    public Element toHrDomElement(Document doc) {
        Element ele = doc.createElement(XmlUtils.HR_TAG_THIRD_PARTY_VERIFICATION);
        ele.setAttribute(XmlUtils.HR_ATTR_URL, mUrl);
        return ele;
    }
}
