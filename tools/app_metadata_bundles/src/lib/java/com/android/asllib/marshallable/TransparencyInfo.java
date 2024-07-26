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

/** TransparencyInfo representation containing {@link AppInfo} */
public class TransparencyInfo implements AslMarshallable {

    private final DeveloperInfo mDeveloperInfo;
    private final AppInfo mAppInfo;

    public TransparencyInfo(DeveloperInfo developerInfo, AppInfo appInfo) {
        this.mDeveloperInfo = developerInfo;
        this.mAppInfo = appInfo;
    }

    /** Creates an on-device DOM element from the {@link TransparencyInfo}. */
    public Element toOdDomElement(Document doc) {
        Element transparencyInfoEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_TRANSPARENCY_INFO);
        if (mDeveloperInfo != null) {
            transparencyInfoEle.appendChild(mDeveloperInfo.toOdDomElement(doc));
        }
        if (mAppInfo != null) {
            transparencyInfoEle.appendChild(mAppInfo.toOdDomElement(doc));
        }
        return transparencyInfoEle;
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    public Element toHrDomElement(Document doc) {
        Element transparencyInfoEle = doc.createElement(XmlUtils.HR_TAG_TRANSPARENCY_INFO);
        if (mDeveloperInfo != null) {
            transparencyInfoEle.appendChild(mDeveloperInfo.toHrDomElement(doc));
        }
        if (mAppInfo != null) {
            transparencyInfoEle.appendChild(mAppInfo.toHrDomElement(doc));
        }
        return transparencyInfoEle;
    }
}
