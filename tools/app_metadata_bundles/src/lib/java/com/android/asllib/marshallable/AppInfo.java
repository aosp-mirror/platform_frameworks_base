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

/** AppInfo representation */
public class AppInfo implements AslMarshallable {
    private final String mTitle;
    private final String mDescription;
    private final Boolean mContainsAds;
    private final Boolean mObeyAps;
    private final Boolean mAdsFingerprinting;
    private final Boolean mSecurityFingerprinting;
    private final String mPrivacyPolicy;
    private final List<String> mSecurityEndpoints;
    private final List<String> mFirstPartyEndpoints;
    private final List<String> mServiceProviderEndpoints;
    private final String mCategory;
    private final String mEmail;
    private final String mWebsite;

    private final Boolean mApsCompliant;
    private final String mDeveloperId;
    private final String mApplicationId;

    // private final String mPrivacyPolicy;
    // private final List<String> mFirstPartyEndpoints;
    // private final List<String> mServiceProviderEndpoints;

    public AppInfo(
            String title,
            String description,
            Boolean containsAds,
            Boolean obeyAps,
            Boolean adsFingerprinting,
            Boolean securityFingerprinting,
            String privacyPolicy,
            List<String> securityEndpoints,
            List<String> firstPartyEndpoints,
            List<String> serviceProviderEndpoints,
            String category,
            String email,
            String website,
            Boolean apsCompliant,
            String developerId,
            String applicationId) {
        this.mTitle = title;
        this.mDescription = description;
        this.mContainsAds = containsAds;
        this.mObeyAps = obeyAps;
        this.mAdsFingerprinting = adsFingerprinting;
        this.mSecurityFingerprinting = securityFingerprinting;
        this.mPrivacyPolicy = privacyPolicy;
        this.mSecurityEndpoints = securityEndpoints;
        this.mFirstPartyEndpoints = firstPartyEndpoints;
        this.mServiceProviderEndpoints = serviceProviderEndpoints;
        this.mCategory = category;
        this.mEmail = email;
        this.mWebsite = website;
        this.mApsCompliant = apsCompliant;
        this.mDeveloperId = developerId;
        this.mApplicationId = applicationId;
    }

    /** Creates an on-device DOM element from the {@link SafetyLabels}. */
    public Element toOdDomElement(Document doc) {
        Element appInfoEle = XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_APP_INFO);

        if (this.mTitle != null) {
            appInfoEle.appendChild(XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_TITLE, mTitle));
        }
        if (this.mDescription != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_DESCRIPTION, mDescription));
        }
        if (this.mContainsAds != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdBooleanEle(doc, XmlUtils.OD_NAME_CONTAINS_ADS, mContainsAds));
        }
        if (this.mObeyAps != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdBooleanEle(doc, XmlUtils.OD_NAME_OBEY_APS, mObeyAps));
        }
        if (this.mAdsFingerprinting != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdBooleanEle(
                            doc, XmlUtils.OD_NAME_ADS_FINGERPRINTING, mAdsFingerprinting));
        }
        if (this.mSecurityFingerprinting != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdBooleanEle(
                            doc,
                            XmlUtils.OD_NAME_SECURITY_FINGERPRINTING,
                            mSecurityFingerprinting));
        }
        if (this.mPrivacyPolicy != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(
                            doc, XmlUtils.OD_NAME_PRIVACY_POLICY, mPrivacyPolicy));
        }
        if (this.mSecurityEndpoints != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdArray(
                            doc,
                            XmlUtils.OD_TAG_STRING_ARRAY,
                            XmlUtils.OD_NAME_SECURITY_ENDPOINTS,
                            mSecurityEndpoints));
        }
        if (this.mFirstPartyEndpoints != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdArray(
                            doc,
                            XmlUtils.OD_TAG_STRING_ARRAY,
                            XmlUtils.OD_NAME_FIRST_PARTY_ENDPOINTS,
                            mFirstPartyEndpoints));
        }
        if (this.mServiceProviderEndpoints != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdArray(
                            doc,
                            XmlUtils.OD_TAG_STRING_ARRAY,
                            XmlUtils.OD_NAME_SERVICE_PROVIDER_ENDPOINTS,
                            mServiceProviderEndpoints));
        }
        if (this.mCategory != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_CATEGORY, this.mCategory));
        }
        if (this.mEmail != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_EMAIL, this.mEmail));
        }
        if (this.mWebsite != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_WEBSITE, this.mWebsite));
        }

        if (this.mApsCompliant != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdBooleanEle(
                            doc, XmlUtils.OD_NAME_APS_COMPLIANT, mApsCompliant));
        }
        if (this.mDeveloperId != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_DEVELOPER_ID, mDeveloperId));
        }
        if (this.mApplicationId != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(
                            doc, XmlUtils.OD_NAME_APPLICATION_ID, mApplicationId));
        }
        return appInfoEle;
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    public Element toHrDomElement(Document doc) {
        Element appInfoEle = doc.createElement(XmlUtils.HR_TAG_APP_INFO);

        if (this.mTitle != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_TITLE, this.mTitle);
        }
        if (this.mDescription != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_DESCRIPTION, this.mDescription);
        }
        if (this.mContainsAds != null) {
            appInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_CONTAINS_ADS, String.valueOf(this.mContainsAds));
        }
        if (this.mObeyAps != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_OBEY_APS, String.valueOf(this.mObeyAps));
        }
        if (this.mAdsFingerprinting != null) {
            appInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_ADS_FINGERPRINTING, String.valueOf(this.mAdsFingerprinting));
        }
        if (this.mSecurityFingerprinting != null) {
            appInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_SECURITY_FINGERPRINTING,
                    String.valueOf(this.mSecurityFingerprinting));
        }
        if (this.mPrivacyPolicy != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_PRIVACY_POLICY, this.mPrivacyPolicy);
        }
        if (this.mSecurityEndpoints != null) {
            appInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_SECURITY_ENDPOINTS, String.join("|", this.mSecurityEndpoints));
        }
        if (this.mCategory != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_CATEGORY, this.mCategory);
        }
        if (this.mEmail != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_EMAIL, this.mEmail);
        }
        if (this.mWebsite != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_WEBSITE, this.mWebsite);
        }

        if (this.mApsCompliant != null) {
            appInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_APS_COMPLIANT, String.valueOf(this.mApsCompliant));
        }
        if (this.mFirstPartyEndpoints != null) {
            appInfoEle.appendChild(
                    XmlUtils.createHrArray(
                            doc, XmlUtils.HR_TAG_FIRST_PARTY_ENDPOINTS, mFirstPartyEndpoints));
        }
        if (this.mServiceProviderEndpoints != null) {
            appInfoEle.appendChild(
                    XmlUtils.createHrArray(
                            doc,
                            XmlUtils.HR_TAG_SERVICE_PROVIDER_ENDPOINTS,
                            mServiceProviderEndpoints));
        }
        if (this.mDeveloperId != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_DEVELOPER_ID, this.mDeveloperId);
        }
        if (this.mApplicationId != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_APPLICATION_ID, this.mApplicationId);
        }

        return appInfoEle;
    }
}
