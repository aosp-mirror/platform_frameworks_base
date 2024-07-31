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
    public Element toOdDomElement(Document doc) {
        Element aslEle = doc.createElement(XmlUtils.OD_TAG_BUNDLE);
        aslEle.appendChild(XmlUtils.createOdLongEle(doc, XmlUtils.OD_NAME_VERSION, mVersion));
        if (mSafetyLabels != null) {
            aslEle.appendChild(mSafetyLabels.toOdDomElement(doc));
        }
        if (mSystemAppSafetyLabel != null) {
            aslEle.appendChild(mSystemAppSafetyLabel.toOdDomElement(doc));
        }
        if (mTransparencyInfo != null) {
            aslEle.appendChild(mTransparencyInfo.toOdDomElement(doc));
        }
        return aslEle;
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    public Element toHrDomElement(Document doc) {
        Element aslEle = doc.createElement(XmlUtils.HR_TAG_APP_METADATA_BUNDLES);
        aslEle.setAttribute(XmlUtils.HR_ATTR_VERSION, String.valueOf(mVersion));
        if (mSafetyLabels != null) {
            aslEle.appendChild(mSafetyLabels.toHrDomElement(doc));
        }
        if (mSystemAppSafetyLabel != null) {
            aslEle.appendChild(mSystemAppSafetyLabel.toHrDomElement(doc));
        }
        if (mTransparencyInfo != null) {
            aslEle.appendChild(mTransparencyInfo.toHrDomElement(doc));
        }
        return aslEle;
    }
}
