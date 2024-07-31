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

import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Element;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppInfoFactory implements AslMarshallableFactory<AppInfo> {
    // We don't need to support V1 for HR.
    private final Map<Long, Set<String>> mRecognizedHrAttrs =
            Map.ofEntries(
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.HR_ATTR_APS_COMPLIANT,
                                    XmlUtils.HR_ATTR_PRIVACY_POLICY,
                                    XmlUtils.HR_ATTR_DEVELOPER_ID,
                                    XmlUtils.HR_ATTR_APPLICATION_ID)));
    private final Map<Long, Set<String>> mRequiredHrAttrs =
            Map.ofEntries(
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.HR_ATTR_APS_COMPLIANT,
                                    XmlUtils.HR_ATTR_PRIVACY_POLICY,
                                    XmlUtils.HR_ATTR_DEVELOPER_ID,
                                    XmlUtils.HR_ATTR_APPLICATION_ID)));
    private final Map<Long, Set<String>> mRecognizedHrEles =
            Map.ofEntries(
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.HR_TAG_FIRST_PARTY_ENDPOINTS,
                                    XmlUtils.HR_TAG_SERVICE_PROVIDER_ENDPOINTS)));
    private final Map<Long, Set<String>> mRequiredHrEles =
            Map.ofEntries(
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.HR_TAG_FIRST_PARTY_ENDPOINTS,
                                    XmlUtils.HR_TAG_SERVICE_PROVIDER_ENDPOINTS)));
    private final Map<Long, Set<String>> mRecognizedOdEleNames =
            Map.ofEntries(
                    Map.entry(
                            1L,
                            Set.of(
                                    XmlUtils.OD_NAME_TITLE,
                                    XmlUtils.OD_NAME_DESCRIPTION,
                                    XmlUtils.OD_NAME_CONTAINS_ADS,
                                    XmlUtils.OD_NAME_OBEY_APS,
                                    XmlUtils.OD_NAME_ADS_FINGERPRINTING,
                                    XmlUtils.OD_NAME_SECURITY_FINGERPRINTING,
                                    XmlUtils.OD_NAME_PRIVACY_POLICY,
                                    XmlUtils.OD_NAME_SECURITY_ENDPOINTS,
                                    XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINTS,
                                    XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINTS,
                                    XmlUtils.OD_NAME_CATEGORY,
                                    XmlUtils.OD_NAME_EMAIL,
                                    XmlUtils.OD_NAME_WEBSITE)),
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.OD_NAME_APS_COMPLIANT,
                                    XmlUtils.OD_NAME_PRIVACY_POLICY,
                                    XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINTS,
                                    XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINTS,
                                    XmlUtils.OD_NAME_DEVELOPER_ID,
                                    XmlUtils.OD_NAME_APPLICATION_ID)));
    private final Map<Long, Set<String>> mRequiredOdEles =
            Map.ofEntries(
                    Map.entry(
                            1L,
                            Set.of(
                                    XmlUtils.OD_NAME_TITLE,
                                    XmlUtils.OD_NAME_DESCRIPTION,
                                    XmlUtils.OD_NAME_CONTAINS_ADS,
                                    XmlUtils.OD_NAME_OBEY_APS,
                                    XmlUtils.OD_NAME_ADS_FINGERPRINTING,
                                    XmlUtils.OD_NAME_SECURITY_FINGERPRINTING,
                                    XmlUtils.OD_NAME_PRIVACY_POLICY,
                                    XmlUtils.OD_NAME_SECURITY_ENDPOINTS,
                                    XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINTS,
                                    XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINTS,
                                    XmlUtils.OD_NAME_CATEGORY)),
                    Map.entry(
                            2L,
                            Set.of(
                                    XmlUtils.OD_NAME_APS_COMPLIANT,
                                    XmlUtils.OD_NAME_PRIVACY_POLICY,
                                    XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINTS,
                                    XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINTS,
                                    XmlUtils.OD_NAME_DEVELOPER_ID,
                                    XmlUtils.OD_NAME_APPLICATION_ID)));

    /** Creates a {@link AppInfo} from the human-readable DOM element. */
    public AppInfo createFromHrElement(Element appInfoEle, long version)
            throws MalformedXmlException {
        if (appInfoEle == null) {
            return null;
        }
        XmlUtils.throwIfExtraneousAttributes(
                appInfoEle, XmlUtils.getMostRecentVersion(mRecognizedHrAttrs, version));
        XmlUtils.throwIfExtraneousChildrenHr(
                appInfoEle, XmlUtils.getMostRecentVersion(mRecognizedHrEles, version));

        var requiredHrAttrs = XmlUtils.getMostRecentVersion(mRequiredHrAttrs, version);
        var requiredHrEles = XmlUtils.getMostRecentVersion(mRequiredHrEles, version);

        String title = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_TITLE, requiredHrAttrs);
        String description =
                XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_DESCRIPTION, requiredHrAttrs);
        Boolean containsAds =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_CONTAINS_ADS, requiredHrAttrs);
        Boolean obeyAps =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_OBEY_APS, requiredHrAttrs);
        Boolean adsFingerprinting =
                XmlUtils.getBoolAttr(
                        appInfoEle, XmlUtils.HR_ATTR_ADS_FINGERPRINTING, requiredHrAttrs);
        Boolean securityFingerprinting =
                XmlUtils.getBoolAttr(
                        appInfoEle, XmlUtils.HR_ATTR_SECURITY_FINGERPRINTING, requiredHrAttrs);
        String privacyPolicy =
                XmlUtils.getStringAttr(
                        appInfoEle, XmlUtils.HR_ATTR_PRIVACY_POLICY, requiredHrAttrs);
        List<String> securityEndpoints =
                XmlUtils.getPipelineSplitAttr(
                        appInfoEle, XmlUtils.HR_ATTR_SECURITY_ENDPOINTS, requiredHrAttrs);
        String category =
                XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_CATEGORY, requiredHrAttrs);
        String email = XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_EMAIL, requiredHrAttrs);
        String website =
                XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_WEBSITE, requiredHrAttrs);
        String developerId =
                XmlUtils.getStringAttr(appInfoEle, XmlUtils.HR_ATTR_DEVELOPER_ID, requiredHrAttrs);
        String applicationId =
                XmlUtils.getStringAttr(
                        appInfoEle, XmlUtils.HR_ATTR_APPLICATION_ID, requiredHrAttrs);

        Boolean apsCompliant =
                XmlUtils.getBoolAttr(appInfoEle, XmlUtils.HR_ATTR_APS_COMPLIANT, requiredHrAttrs);
        List<String> firstPartyEndpoints =
                XmlUtils.getHrItemsAsStrings(
                        appInfoEle, XmlUtils.HR_TAG_FIRST_PARTY_ENDPOINTS, requiredHrEles);
        List<String> serviceProviderEndpoints =
                XmlUtils.getHrItemsAsStrings(
                        appInfoEle, XmlUtils.HR_TAG_SERVICE_PROVIDER_ENDPOINTS, requiredHrEles);

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
                website,
                apsCompliant,
                developerId,
                applicationId);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    public AppInfo createFromOdElement(Element appInfoEle, long version)
            throws MalformedXmlException {
        if (appInfoEle == null) {
            return null;
        }
        XmlUtils.throwIfExtraneousChildrenOd(
                appInfoEle, XmlUtils.getMostRecentVersion(mRecognizedOdEleNames, version));
        var requiredOdEles = XmlUtils.getMostRecentVersion(mRequiredOdEles, version);

        String title = XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_TITLE, requiredOdEles);
        String description =
                XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_DESCRIPTION, requiredOdEles);
        Boolean containsAds =
                XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_CONTAINS_ADS, requiredOdEles);
        Boolean obeyAps =
                XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_OBEY_APS, requiredOdEles);
        Boolean adsFingerprinting =
                XmlUtils.getOdBoolEle(
                        appInfoEle, XmlUtils.OD_NAME_ADS_FINGERPRINTING, requiredOdEles);
        Boolean securityFingerprinting =
                XmlUtils.getOdBoolEle(
                        appInfoEle, XmlUtils.OD_NAME_SECURITY_FINGERPRINTING, requiredOdEles);
        String privacyPolicy =
                XmlUtils.getOdStringEle(
                        appInfoEle, XmlUtils.OD_NAME_PRIVACY_POLICY, requiredOdEles);
        List<String> securityEndpoints =
                XmlUtils.getOdStringArray(
                        appInfoEle, XmlUtils.OD_NAME_SECURITY_ENDPOINTS, requiredOdEles);
        String category =
                XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_CATEGORY, requiredOdEles);
        String email = XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_EMAIL, requiredOdEles);
        String website =
                XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_WEBSITE, requiredOdEles);
        String developerId =
                XmlUtils.getOdStringEle(appInfoEle, XmlUtils.OD_NAME_DEVELOPER_ID, requiredOdEles);
        String applicationId =
                XmlUtils.getOdStringEle(
                        appInfoEle, XmlUtils.OD_NAME_APPLICATION_ID, requiredOdEles);

        Boolean apsCompliant =
                XmlUtils.getOdBoolEle(appInfoEle, XmlUtils.OD_NAME_APS_COMPLIANT, requiredOdEles);
        List<String> firstPartyEndpoints =
                XmlUtils.getOdStringArray(
                        appInfoEle, XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINTS, requiredOdEles);
        List<String> serviceProviderEndpoints =
                XmlUtils.getOdStringArray(
                        appInfoEle, XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINTS, requiredOdEles);

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
                website,
                apsCompliant,
                developerId,
                applicationId);
    }
}
