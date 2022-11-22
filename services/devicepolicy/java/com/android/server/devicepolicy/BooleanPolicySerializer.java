/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.devicepolicy;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

final class BooleanPolicySerializer extends PolicySerializer<Boolean> {

    @Override
    void saveToXml(TypedXmlSerializer serializer, String attributeName, Boolean value)
            throws IOException {
        serializer.attributeBoolean(/* namespace= */ null, attributeName, value);
    }

    @Override
    Boolean readFromXml(TypedXmlPullParser parser, String attributeName)
            throws XmlPullParserException {
        return parser.getAttributeBoolean(/* namespace= */ null, attributeName);
    }
}
