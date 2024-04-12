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

public class ThirdPartyVerificationFactory
        implements AslMarshallableFactory<ThirdPartyVerification> {

    /** Creates a {@link ThirdPartyVerification} from the human-readable DOM element. */
    @Override
    public ThirdPartyVerification createFromHrElements(List<Element> elements)
            throws MalformedXmlException {
        Element ele = XmlUtils.getSingleElement(elements);
        if (ele == null) {
            AslgenUtil.logI("No ThirdPartyVerification found in hr format.");
            return null;
        }

        String url = XmlUtils.getStringAttr(ele, XmlUtils.HR_ATTR_URL, true);
        return new ThirdPartyVerification(url);
    }

    /** Creates an {@link AslMarshallableFactory} from on-device DOM elements */
    @Override
    public ThirdPartyVerification createFromOdElements(List<Element> elements)
            throws MalformedXmlException {
        Element ele = XmlUtils.getSingleElement(elements);
        if (ele == null) {
            AslgenUtil.logI("No ThirdPartyVerification found in od format.");
            return null;
        }

        String url = XmlUtils.getOdStringEle(ele, XmlUtils.OD_NAME_URL, true);
        return new ThirdPartyVerification(url);
    }
}
