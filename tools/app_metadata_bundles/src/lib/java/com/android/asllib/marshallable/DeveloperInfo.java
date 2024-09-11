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

/** DeveloperInfo representation */
public class DeveloperInfo implements AslMarshallable {
    public enum DeveloperRelationship {
        OEM(0),
        ODM(1),
        SOC(2),
        OTA(3),
        CARRIER(4),
        AOSP(5),
        OTHER(6);

        private final int mValue;

        DeveloperRelationship(int value) {
            this.mValue = value;
        }

        /** Get the int value associated with the DeveloperRelationship. */
        public int getValue() {
            return mValue;
        }

        /** Get the DeveloperRelationship associated with the int value. */
        public static DeveloperInfo.DeveloperRelationship forValue(int value) {
            for (DeveloperInfo.DeveloperRelationship e : values()) {
                if (e.getValue() == value) {
                    return e;
                }
            }
            throw new IllegalArgumentException("No DeveloperRelationship enum for value: " + value);
        }

        /** Get the DeveloperRelationship associated with the human-readable String. */
        public static DeveloperInfo.DeveloperRelationship forString(String s) {
            for (DeveloperInfo.DeveloperRelationship e : values()) {
                if (e.toString().equals(s)) {
                    return e;
                }
            }
            throw new IllegalArgumentException("No DeveloperRelationship enum for str: " + s);
        }

        /** Human-readable String representation of DeveloperRelationship. */
        public String toString() {
            return this.name().toLowerCase();
        }
    }

    private final String mName;
    private final String mEmail;
    private final String mAddress;
    private final String mCountryRegion;
    private final DeveloperRelationship mDeveloperRelationship;
    private final String mWebsite;
    private final String mAppDeveloperRegistryId;

    public DeveloperInfo(
            String name,
            String email,
            String address,
            String countryRegion,
            DeveloperRelationship developerRelationship,
            String website,
            String appDeveloperRegistryId) {
        this.mName = name;
        this.mEmail = email;
        this.mAddress = address;
        this.mCountryRegion = countryRegion;
        this.mDeveloperRelationship = developerRelationship;
        this.mWebsite = website;
        this.mAppDeveloperRegistryId = appDeveloperRegistryId;
    }

    /** Creates an on-device DOM element from the {@link SafetyLabels}. */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element developerInfoEle =
                XmlUtils.createPbundleEleWithName(doc, XmlUtils.OD_NAME_DEVELOPER_INFO);
        if (mName != null) {
            developerInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_NAME, mName));
        }
        if (mEmail != null) {
            developerInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_EMAIL, mEmail));
        }
        if (mAddress != null) {
            developerInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_ADDRESS, mAddress));
        }
        if (mCountryRegion != null) {
            developerInfoEle.appendChild(
                    XmlUtils.createOdStringEle(
                            doc, XmlUtils.OD_NAME_COUNTRY_REGION, mCountryRegion));
        }
        if (mDeveloperRelationship != null) {
            developerInfoEle.appendChild(
                    XmlUtils.createOdLongEle(
                            doc,
                            XmlUtils.OD_NAME_DEVELOPER_RELATIONSHIP,
                            mDeveloperRelationship.getValue()));
        }
        if (mWebsite != null) {
            developerInfoEle.appendChild(
                    XmlUtils.createOdStringEle(doc, XmlUtils.OD_NAME_WEBSITE, mWebsite));
        }
        if (mAppDeveloperRegistryId != null) {
            developerInfoEle.appendChild(
                    XmlUtils.createOdStringEle(
                            doc,
                            XmlUtils.OD_NAME_APP_DEVELOPER_REGISTRY_ID,
                            mAppDeveloperRegistryId));
        }

        return XmlUtils.listOf(developerInfoEle);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        Element developerInfoEle = doc.createElement(XmlUtils.HR_TAG_DEVELOPER_INFO);
        if (mName != null) {
            developerInfoEle.setAttribute(XmlUtils.HR_ATTR_NAME, mName);
        }
        if (mEmail != null) {
            developerInfoEle.setAttribute(XmlUtils.HR_ATTR_EMAIL, mEmail);
        }
        if (mAddress != null) {
            developerInfoEle.setAttribute(XmlUtils.HR_ATTR_ADDRESS, mAddress);
        }
        if (mCountryRegion != null) {
            developerInfoEle.setAttribute(XmlUtils.HR_ATTR_COUNTRY_REGION, mCountryRegion);
        }
        if (mDeveloperRelationship != null) {
            developerInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_DEVELOPER_RELATIONSHIP, mDeveloperRelationship.toString());
        }
        if (mWebsite != null) {
            developerInfoEle.setAttribute(XmlUtils.HR_ATTR_WEBSITE, mWebsite);
        }
        if (mAppDeveloperRegistryId != null) {
            developerInfoEle.setAttribute(
                    XmlUtils.HR_ATTR_APP_DEVELOPER_REGISTRY_ID, mAppDeveloperRegistryId);
        }

        return XmlUtils.listOf(developerInfoEle);
    }
}
