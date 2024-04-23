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

public class DeveloperInfoFactory implements AslMarshallableFactory<DeveloperInfo> {

    /** Creates a {@link DeveloperInfo} from the human-readable DOM element. */
    @Override
    public DeveloperInfo createFromHrElements(List<Element> elements) throws MalformedXmlException {
        Element developerInfoEle = XmlUtils.getSingleElement(elements);
        if (developerInfoEle == null) {
            AslgenUtil.logI("No DeveloperInfo found in hr format.");
            return null;
        }
        String name = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_NAME, true);
        String email = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_EMAIL, true);
        String address = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_ADDRESS, true);
        String countryRegion =
                XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_COUNTRY_REGION, true);
        DeveloperInfo.DeveloperRelationship developerRelationship =
                DeveloperInfo.DeveloperRelationship.forString(
                        XmlUtils.getStringAttr(
                                developerInfoEle, XmlUtils.HR_ATTR_DEVELOPER_RELATIONSHIP, true));
        String website = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_WEBSITE, false);
        String appDeveloperRegistryId =
                XmlUtils.getStringAttr(
                        developerInfoEle, XmlUtils.HR_ATTR_APP_DEVELOPER_REGISTRY_ID, false);

        return new DeveloperInfo(
                name,
                email,
                address,
                countryRegion,
                developerRelationship,
                website,
                appDeveloperRegistryId);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public DeveloperInfo createFromOdElements(List<Element> elements) throws MalformedXmlException {
        Element developerInfoEle = XmlUtils.getSingleElement(elements);
        if (developerInfoEle == null) {
            AslgenUtil.logI("No DeveloperInfo found in od format.");
            return null;
        }
        String name = XmlUtils.getOdStringEle(developerInfoEle, XmlUtils.OD_NAME_NAME, true);
        String email = XmlUtils.getOdStringEle(developerInfoEle, XmlUtils.OD_NAME_EMAIL, true);
        String address = XmlUtils.getOdStringEle(developerInfoEle, XmlUtils.OD_NAME_ADDRESS, true);
        String countryRegion =
                XmlUtils.getOdStringEle(developerInfoEle, XmlUtils.OD_NAME_COUNTRY_REGION, true);
        DeveloperInfo.DeveloperRelationship developerRelationship =
                DeveloperInfo.DeveloperRelationship.forValue(
                        (int)
                                (long)
                                        XmlUtils.getOdLongEle(
                                                developerInfoEle,
                                                XmlUtils.OD_NAME_DEVELOPER_RELATIONSHIP,
                                                true));
        String website = XmlUtils.getOdStringEle(developerInfoEle, XmlUtils.OD_NAME_WEBSITE, false);
        String appDeveloperRegistryId =
                XmlUtils.getOdStringEle(
                        developerInfoEle, XmlUtils.OD_NAME_APP_DEVELOPER_REGISTRY_ID, false);

        return new DeveloperInfo(
                name,
                email,
                address,
                countryRegion,
                developerRelationship,
                website,
                appDeveloperRegistryId);
    }
}
