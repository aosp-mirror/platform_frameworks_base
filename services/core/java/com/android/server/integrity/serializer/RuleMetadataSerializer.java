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

package com.android.server.integrity.serializer;

import static com.android.server.integrity.parser.RuleMetadataParser.RULE_PROVIDER_TAG;
import static com.android.server.integrity.parser.RuleMetadataParser.VERSION_TAG;

import android.util.Xml;

import com.android.server.integrity.model.RuleMetadata;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Helper class for writing rule metadata. */
public class RuleMetadataSerializer {
    /** Serialize the rule metadata to an output stream. */
    public static void serialize(RuleMetadata ruleMetadata, OutputStream outputStream)
            throws IOException {
        XmlSerializer xmlSerializer = Xml.newSerializer();
        xmlSerializer.setOutput(outputStream, StandardCharsets.UTF_8.name());

        serializeTaggedValue(xmlSerializer, RULE_PROVIDER_TAG, ruleMetadata.getRuleProvider());
        serializeTaggedValue(xmlSerializer, VERSION_TAG, ruleMetadata.getVersion());

        xmlSerializer.endDocument();
    }

    private static void serializeTaggedValue(XmlSerializer xmlSerializer, String tag, String value)
            throws IOException {
        xmlSerializer.startTag(/* namespace= */ null, tag);
        xmlSerializer.text(value);
        xmlSerializer.endTag(/* namespace= */ null, tag);
    }
}
