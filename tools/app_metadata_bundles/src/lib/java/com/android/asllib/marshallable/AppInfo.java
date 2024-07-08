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
    private final Boolean mApsCompliant;
    private final String mPrivacyPolicy;
    private final List<String> mFirstPartyEndpoints;
    private final List<String> mServiceProviderEndpoints;

    public AppInfo(
            Boolean apsCompliant,
            String privacyPolicy,
            List<String> firstPartyEndpoints,
            List<String> serviceProviderEndpoints) {
        this.mApsCompliant = apsCompliant;
        this.mPrivacyPolicy = privacyPolicy;
        this.mFirstPartyEndpoints = firstPartyEndpoints;
        this.mServiceProviderEndpoints = serviceProviderEndpoints;
    }

    /** Creates an on-device DOM element from the {@link SafetyLabels}. */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element appInfoEle = XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_APP_INFO);
        if (this.mApsCompliant != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdBooleanEle(
                            doc, XmlUtils.OD_NAME_APS_COMPLIANT, mApsCompliant));
        }
        if (this.mPrivacyPolicy != null) {
            appInfoEle.appendChild(
                    XmlUtils.createOdStringEle(
                            doc, XmlUtils.OD_NAME_PRIVACY_POLICY, mPrivacyPolicy));
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
        return XmlUtils.listOf(appInfoEle);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        Element appInfoEle = doc.createElement(XmlUtils.HR_TAG_APP_INFO);
        if (this.mApsCompliant != null) {
            appInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_APS_COMPLIANT, String.valueOf(this.mApsCompliant));
        }
        if (this.mPrivacyPolicy != null) {
            appInfoEle.setAttribute(XmlUtils.HR_ATTR_PRIVACY_POLICY, this.mPrivacyPolicy);
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

        return XmlUtils.listOf(appInfoEle);
    }
}
