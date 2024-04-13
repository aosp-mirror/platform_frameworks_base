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

        String title = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_TITLE, true);
        String description = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_DESCRIPTION, true);
        Boolean containsAds = XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_CONTAINS_ADS, true);
        Boolean obeyAps = XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_OBEY_APS, true);
        Boolean adsFingerprinting =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_ADS_FINGERPRINTING, true);
        Boolean securityFingerprinting =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_SECURITY_FINGERPRINTING, true);
        String privacyPolicy =
                XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_PRIVACY_POLICY, true);
        List<String> securityEndpoints =
                XmlUtils.getPipelineSplitAttr(
                        appInfoEle, XmlUtils.HR_ATTR_SECURITY_ENDPOINTS, true);
        List<String> firstPartyEndpoints =
                XmlUtils.getPipelineSplitAttr(
                        appInfoEle, XmlUtils.HR_ATTR_FIRST_PARTY_ENDPOINTS, true);
        List<String> serviceProviderEndpoints =
                XmlUtils.getPipelineSplitAttr(
                        appInfoEle, XmlUtils.HR_ATTR_SERVICE_PROVIDER_ENDPOINTS, true);
        String category = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_CATEGORY, true);
        String email = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_EMAIL, true);
        String website = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_WEBSITE, false);

        return new AppInfo(
                title,
                description,
                containsAds,
                obeyAps,
                adsFingerprinting,
                securityFingerprinting,
                privacyPolicy,
                securityEndpoints,
                firstPartyEndpoints,
                serviceProviderEndpoints,
                category,
                email,
                website);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public AppInfo createFromOdElements(List<Element> elements) throws MalformedXmlException {
        Element appInfoEle = XmlUtils.getSingleElement(elements);
        if (appInfoEle == null) {
            AslgenUtil.logI("No AppInfo found in od format.");
            return null;
        }

        String title = XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_TITLE, true);
        String description =
                XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_DESCRIPTION, true);
        Boolean containsAds =
                XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_CONTAINS_ADS, true);
        Boolean obeyAps = XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_OBEY_APS, true);
        Boolean adsFingerprinting =
                XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_ADS_FINGERPRINTING, true);
        Boolean securityFingerprinting =
                XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_SECURITY_FINGERPRINTING, true);
        String privacyPolicy =
                XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_PRIVACY_POLICY, true);
        List<String> securityEndpoints =
                XmlUtils.getOdStringArray(appInfoEle, XmlUtils.OD_NAME_SECURITY_ENDPOINT, true);
        List<String> firstPartyEndpoints =
                XmlUtils.getOdStringArray(appInfoEle, XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINT, true);
        List<String> serviceProviderEndpoints =
                XmlUtils.getOdStringArray(
                        appInfoEle, XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINT, true);
        String category = XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_CATEGORY, true);
        String email = XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_EMAIL, true);
        String website = XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_WEBSITE, false);

        return new AppInfo(
                title,
                description,
                containsAds,
                obeyAps,
                adsFingerprinting,
                securityFingerprinting,
                privacyPolicy,
                securityEndpoints,
                firstPartyEndpoints,
                serviceProviderEndpoints,
                category,
                email,
                website);
    }
}
