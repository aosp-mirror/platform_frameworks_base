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

/** Security Labels representation */
public class SecurityLabels implements AslMarshallable {

    private final Boolean mIsDataDeletable;
    private final Boolean mIsDataEncrypted;

    public SecurityLabels(Boolean isDataDeletable, Boolean isDataEncrypted) {
        this.mIsDataDeletable = isDataDeletable;
        this.mIsDataEncrypted = isDataEncrypted;
    }

    /** Creates an on-device DOM element from the {@link SecurityLabels}. */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element ele = XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_SECURITY_LABELS);
        if (mIsDataDeletable != null) {
            ele.appendChild(
                    XmlUtils.createOdBooleanEle(
                            doc, XmlUtils.OD_NAME_IS_DATA_DELETABLE, mIsDataDeletable));
        }
        if (mIsDataEncrypted != null) {
            ele.appendChild(
                    XmlUtils.createOdBooleanEle(
                            doc, XmlUtils.OD_NAME_IS_DATA_ENCRYPTED, mIsDataEncrypted));
        }
        return XmlUtils.listOf(ele);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        Element ele = doc.createElement(XmlUtils.HR_TAG_SECURITY_LABELS);
        if (mIsDataDeletable != null) {
            ele.setAttribute(XmlUtils.HR_ATTR_IS_DATA_DELETABLE, String.valueOf(mIsDataDeletable));
        }
        if (mIsDataEncrypted != null) {
            ele.setAttribute(XmlUtils.HR_ATTR_IS_DATA_ENCRYPTED, String.valueOf(mIsDataEncrypted));
        }
        return XmlUtils.listOf(ele);
    }
}
