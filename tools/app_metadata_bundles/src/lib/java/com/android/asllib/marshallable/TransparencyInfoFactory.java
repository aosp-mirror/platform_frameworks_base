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

import com.android.asllib.util.AslgenUtil;
import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Element;

import java.util.List;

public class TransparencyInfoFactory implements AslMarshallableFactory<TransparencyInfo> {

    /** Creates a {@link TransparencyInfo} from the human-readable DOM element. */
    @Override
    public TransparencyInfo createFromHrElements(List<Element> elements)
            throws MalformedXmlException {
        Element transparencyInfoEle = XmlUtils.getSingleElement(elements);
        if (transparencyInfoEle == null) {
            AslgenUtil.logI("No TransparencyInfo found in hr format.");
            return null;
        }

        Element developerInfoEle =
                XmlUtils.getSingleChildElement(
                        transparencyInfoEle, XmlUtils.HR_TAG_DEVELOPER_INFO, false);
        DeveloperInfo developerInfo =
                new DeveloperInfoFactory().createFromHrElements(XmlUtils.listOf(developerInfoEle));

        Element appInfoEle =
                XmlUtils.getSingleChildElement(
                        transparencyInfoEle, XmlUtils.HR_TAG_APP_INFO, false);
        AppInfo appInfo = new AppInfoFactory().createFromHrElements(XmlUtils.listOf(appInfoEle));

        return new TransparencyInfo(developerInfo, appInfo);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public TransparencyInfo createFromOdElements(List<Element> elements)
            throws MalformedXmlException {
        Element transparencyInfoEle = XmlUtils.getSingleElement(elements);
        if (transparencyInfoEle == null) {
            AslgenUtil.logI("No TransparencyInfo found in od format.");
            return null;
        }

        Element developerInfoEle =
                XmlUtils.getOdPbundleWithName(
                        transparencyInfoEle, XmlUtils.OD_NAME_DEVELOPER_INFO, false);
        DeveloperInfo developerInfo =
                new DeveloperInfoFactory().createFromOdElements(XmlUtils.listOf(developerInfoEle));

        Element appInfoEle =
                XmlUtils.getOdPbundleWithName(
                        transparencyInfoEle, XmlUtils.OD_NAME_APP_INFO, false);
        AppInfo appInfo = new AppInfoFactory().createFromOdElements(XmlUtils.listOf(appInfoEle));

        return new TransparencyInfo(developerInfo, appInfo);
    }
}
