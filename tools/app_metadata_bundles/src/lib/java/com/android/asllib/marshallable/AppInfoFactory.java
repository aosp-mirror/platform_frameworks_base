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

public class AppInfoFactory implements AslMarshallableFactory<AppInfo> {

    /** Creates a {@link AppInfo} from the human-readable DOM element. */
    @Override
    public AppInfo createFromHrElements(List<Element> elements) throws MalformedXmlException {
        Element appInfoEle = XmlUtils.getSingleElement(elements);
        if (appInfoEle == null) {
            AslgenUtil.logI("No AppInfo found in hr format.");
            return null;
        }

        Boolean apsCompliant =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_APS_COMPLIANT, true);
        String privacyPolicy =
                XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_PRIVACY_POLICY, true);
        List<String> firstPartyEndpoints =
                XmlUtils.getHrItemsAsStrings(
                        appInfoEle, XmlUtils.HR_TAG_FIRST_PARTY_ENDPOINTS, true);
        List<String> serviceProviderEndpoints =
                XmlUtils.getHrItemsAsStrings(
                        appInfoEle, XmlUtils.HR_TAG_SERVICE_PROVIDER_ENDPOINTS, true);

        return new AppInfo(
                apsCompliant, privacyPolicy, firstPartyEndpoints, serviceProviderEndpoints);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public AppInfo createFromOdElements(List<Element> elements) throws MalformedXmlException {
        Element appInfoEle = XmlUtils.getSingleElement(elements);
        if (appInfoEle == null) {
            AslgenUtil.logI("No AppInfo found in od format.");
            return null;
        }

        Boolean apsCompliant =
                XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_APS_COMPLIANT, true);
        String privacyPolicy =
                XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_PRIVACY_POLICY, true);
        List<String> firstPartyEndpoints =
                XmlUtils.getOdStringArray(appInfoEle, XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINTS, true);
        List<String> serviceProviderEndpoints =
                XmlUtils.getOdStringArray(
                        appInfoEle, XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINTS, true);

        return new AppInfo(
                apsCompliant, privacyPolicy, firstPartyEndpoints, serviceProviderEndpoints);
    }
}
