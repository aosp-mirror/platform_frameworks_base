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

public class AndroidSafetyLabel implements AslMarshallable {

    private final Long mVersion;
    private final SystemAppSafetyLabel mSystemAppSafetyLabel;
    private final SafetyLabels mSafetyLabels;
    private final TransparencyInfo mTransparencyInfo;

    public SafetyLabels getSafetyLabels() {
        return mSafetyLabels;
    }

    public AndroidSafetyLabel(
            Long version,
            SystemAppSafetyLabel systemAppSafetyLabel,
            SafetyLabels safetyLabels,
            TransparencyInfo transparencyInfo) {
        this.mVersion = version;
        this.mSystemAppSafetyLabel = systemAppSafetyLabel;
        this.mSafetyLabels = safetyLabels;
        this.mTransparencyInfo = transparencyInfo;
    }

    /** Creates an on-device DOM element from an {@link AndroidSafetyLabel} */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element aslEle = doc.createElement(XmlUtils.OD_TAG_BUNDLE);
        aslEle.appendChild(XmlUtils.createOdLongEle(doc, XmlUtils.OD_NAME_VERSION, mVersion));
        if (mSafetyLabels != null) {
            XmlUtils.appendChildren(aslEle, mSafetyLabels.toOdDomElements(doc));
        }
        if (mSystemAppSafetyLabel != null) {
            XmlUtils.appendChildren(aslEle, mSystemAppSafetyLabel.toOdDomElements(doc));
        }
        if (mTransparencyInfo != null) {
            XmlUtils.appendChildren(aslEle, mTransparencyInfo.toOdDomElements(doc));
        }
        return XmlUtils.listOf(aslEle);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        Element aslEle = doc.createElement(XmlUtils.HR_TAG_APP_METADATA_BUNDLES);
        aslEle.setAttribute(XmlUtils.HR_ATTR_VERSION, String.valueOf(mVersion));
        if (mSafetyLabels != null) {
            XmlUtils.appendChildren(aslEle, mSafetyLabels.toHrDomElements(doc));
        }
        if (mSystemAppSafetyLabel != null) {
            XmlUtils.appendChildren(aslEle, mSystemAppSafetyLabel.toHrDomElements(doc));
        }
        if (mTransparencyInfo != null) {
            XmlUtils.appendChildren(aslEle, mTransparencyInfo.toHrDomElements(doc));
        }
        return XmlUtils.listOf(aslEle);
    }
}
