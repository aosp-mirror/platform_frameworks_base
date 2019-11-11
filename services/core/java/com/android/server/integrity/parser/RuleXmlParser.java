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

import android.util.Slog;
import android.util.Xml;

import com.android.server.integrity.model.Rule;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class to parse rules into the {@link Rule} model from Xml representation.
 */
public final class RuleXmlParser implements RuleParser {

    public static final String TAG = "RuleXmlParser";

    private static final String RULE_LIST_TAG = "RuleList";
    private static final String RULE_TAG = "Rule";

    @Override
    public List<Rule> parse(String ruleText) {
        try {
            XmlPullParser xmlPullParser = Xml.newPullParser();
            xmlPullParser.setInput(new StringReader(ruleText));
            return parseRules(xmlPullParser);
        } catch (XmlPullParserException | IOException e) {
            Slog.e(TAG, String.format("Unable to read rules from string: %s", ruleText), e);
        }
        return null;
    }

    @Override
    public List<Rule> parse(InputStream inputStream) {
        try {
            XmlPullParser xmlPullParser = Xml.newPullParser();
            xmlPullParser.setInput(inputStream, StandardCharsets.UTF_8.name());
            return parseRules(xmlPullParser);
        } catch (XmlPullParserException | IOException e) {
            Slog.e(TAG, "Unable to read rules from stream", e);
        }
        return null;
    }

    private List<Rule> parseRules(XmlPullParser parser) throws IOException, XmlPullParserException {
        List<Rule> rules = new ArrayList<>();

        // Skipping the first event type, which is always {@link XmlPullParser.START_DOCUMENT}
        parser.next();

        // Processing the first tag; which should always be a <RuleList> tag.
        String nodeName = parser.getName();
        // Validating that the XML is starting with a <RuleList> tag.
        // Note: This is the only breaking validation to run against XML files in the platform.
        // All rules inside are assumed to be validated at the server. If a rule is found to be
        // corrupt in the XML, it will be skipped to the next rule.
        if (!nodeName.equals(RULE_LIST_TAG)) {
            throw new RuntimeException(
                    String.format("Rules must start with <RuleList> tag. Found: %s at %s", nodeName,
                            parser.getPositionDescription()));
        }

        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            nodeName = parser.getName();
            if (eventType != XmlPullParser.START_TAG || !nodeName.equals(RULE_TAG)) {
                continue;
            }
            Rule parsedRule = parseRule(parser);
            if (parsedRule != null) {
                rules.add(parsedRule);
            }
        }

        return rules;
    }

    private Rule parseRule(XmlPullParser parser) {
        // TODO: Implement rule parser.
        return null;
    }
}
