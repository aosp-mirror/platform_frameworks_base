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

        String title = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_TITLE);
        String description = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_DESCRIPTION);
        Boolean containsAds = XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_CONTAINS_ADS, true);
        Boolean obeyAps = XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_OBEY_APS, true);
        Boolean adsFingerprinting =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_ADS_FINGERPRINTING, true);
        Boolean securityFingerprinting =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_SECURITY_FINGERPRINTING, true);
        String privacyPolicy = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_PRIVACY_POLICY);
        List<String> securityEndpoints =
                XmlUtils.getPipelineSplitAttr(
                        appInfoEle, XmlUtils.HR_ATTR_SECURITY_ENDPOINTS, true);
        List<String> firstPartyEndpoints =
                XmlUtils.getPipelineSplitAttr(
                        appInfoEle, XmlUtils.HR_ATTR_FIRST_PARTY_ENDPOINTS, true);
        List<String> serviceProviderEndpoints =
                XmlUtils.getPipelineSplitAttr(
                        appInfoEle, XmlUtils.HR_ATTR_SERVICE_PROVIDER_ENDPOINTS, true);
        String category = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_CATEGORY);
        String email = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_EMAIL);
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
}
