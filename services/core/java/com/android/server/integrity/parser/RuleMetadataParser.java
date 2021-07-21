/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity.parser;

import android.annotation.Nullable;
import android.util.TypedXmlPullParser;
import android.util.Xml;

import com.android.server.integrity.model.RuleMetadata;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;

/** Helper class for parsing rule metadata. */
public class RuleMetadataParser {

    public static final String RULE_PROVIDER_TAG = "P";
    public static final String VERSION_TAG = "V";

    /** Parse the rule metadata from an input stream. */
    @Nullable
    public static RuleMetadata parse(InputStream inputStream)
            throws XmlPullParserException, IOException {

        String ruleProvider = "";
        String version = "";

        TypedXmlPullParser xmlPullParser = Xml.resolvePullParser(inputStream);

        int eventType;
        while ((eventType = xmlPullParser.next()) != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = xmlPullParser.getName();
                switch (tag) {
                    case RULE_PROVIDER_TAG:
                        ruleProvider = xmlPullParser.nextText();
                        break;
                    case VERSION_TAG:
                        version = xmlPullParser.nextText();
                        break;
                    default:
                        throw new IllegalStateException("Unknown tag in metadata: " + tag);
                }
            }
        }

        return new RuleMetadata(ruleProvider, version);
    }
}
