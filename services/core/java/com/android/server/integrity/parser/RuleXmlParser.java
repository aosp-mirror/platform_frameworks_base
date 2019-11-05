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

import com.android.server.integrity.model.AtomicFormula;
import com.android.server.integrity.model.Formula;
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

    // TODO: Use XML attributes
    private static final String RULE_LIST_TAG = "RuleList";
    private static final String RULE_TAG = "Rule";
    private static final String OPEN_FORMULA_TAG = "OpenFormula";
    private static final String ATOMIC_FORMULA_TAG = "AtomicFormula";
    private static final String EFFECT_TAG = "Effect";
    private static final String KEY_TAG = "Key";
    private static final String OPERATOR_TAG = "Operator";
    private static final String VALUE_TAG = "Value";

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

    private static List<Rule> parseRules(XmlPullParser parser)
            throws IOException, XmlPullParserException {
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

    private static Rule parseRule(XmlPullParser parser) {
        try {
            Formula formula = null;
            @Rule.Effect int effect = 0;

            boolean isValid = true;
            int eventType;
            while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
                String nodeName = parser.getName();

                if (eventType == XmlPullParser.END_TAG && parser.getName().equals(RULE_TAG)) {
                    break;
                }

                if (eventType == XmlPullParser.START_TAG) {
                    switch (nodeName) {
                        case OPEN_FORMULA_TAG:
                            formula = parseOpenFormula(parser);
                            break;
                        case ATOMIC_FORMULA_TAG:
                            formula = parseAtomicFormula(parser);
                            break;
                        case EFFECT_TAG:
                            effect = Integer.parseInt(extractValue(parser));
                            break;
                        default:
                            isValid = false;
                    }
                } else {
                    isValid = false;
                }
            }

            return isValid ? new Rule(formula, effect) : null;
        } catch (Exception e) {
            // In case of any exceptions arising from constructing the rule, it will be skipped.
            // Rules are assumed to be validated on the server.
            return null;
        }
    }

    private static Formula parseOpenFormula(XmlPullParser parser) {
        // TODO: Implement open formula parser.
        return null;
    }

    private static Formula parseAtomicFormula(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        @AtomicFormula.Key int key = 0;
        @AtomicFormula.Operator int operator = 0;
        String value = null;

        boolean isValid = true;
        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String nodeName = parser.getName();

            if (eventType == XmlPullParser.END_TAG && parser.getName().equals(ATOMIC_FORMULA_TAG)) {
                break;
            }

            if (eventType == XmlPullParser.START_TAG) {
                switch (nodeName) {
                    case KEY_TAG:
                        key = Integer.parseInt(extractValue(parser));
                        break;
                    case OPERATOR_TAG:
                        operator = Integer.parseInt(extractValue(parser));
                        break;
                    case VALUE_TAG:
                        value = extractValue(parser);
                        break;
                    default:
                        isValid = false;
                }
            }
        }
        return isValid ? constructAtomicFormulaBasedOnKey(key, operator, value) : null;
    }

    private static Formula constructAtomicFormulaBasedOnKey(@AtomicFormula.Key int key,
            @AtomicFormula.Operator int operator, String value) {
        switch (key) {
            case AtomicFormula.PACKAGE_NAME:
            case AtomicFormula.INSTALLER_NAME:
            case AtomicFormula.APP_CERTIFICATE:
            case AtomicFormula.INSTALLER_CERTIFICATE:
                return new AtomicFormula.StringAtomicFormula(key, value);
            case AtomicFormula.PRE_INSTALLED:
                return new AtomicFormula.BooleanAtomicFormula(key, Boolean.parseBoolean(value));
            case AtomicFormula.VERSION_CODE:
                return new AtomicFormula.IntAtomicFormula(key, operator, Integer.parseInt(value));
            default:
                return null;
        }
    }

    private static String extractValue(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String value = null;
        if (parser.next() == XmlPullParser.TEXT) {
            value = parser.getText();
        }
        return parser.next() == XmlPullParser.END_TAG ? value : null;
    }
}
