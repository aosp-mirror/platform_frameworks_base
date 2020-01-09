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

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A helper class to parse rules into the {@link Rule} model from Xml representation. */
public final class RuleXmlParser implements RuleParser {

    public static final String TAG = "RuleXmlParser";

    private static final String NAMESPACE = "";
    private static final String RULE_LIST_TAG = "RL";
    private static final String RULE_TAG = "R";
    private static final String COMPOUND_FORMULA_TAG = "OF";
    private static final String ATOMIC_FORMULA_TAG = "AF";
    private static final String EFFECT_ATTRIBUTE = "E";
    private static final String KEY_ATTRIBUTE = "K";
    private static final String OPERATOR_ATTRIBUTE = "O";
    private static final String VALUE_ATTRIBUTE = "V";
    private static final String CONNECTOR_ATTRIBUTE = "C";
    private static final String IS_HASHED_VALUE_ATTRIBUTE = "H";

    @Override
    public List<Rule> parse(byte[] ruleBytes) throws RuleParseException {
        try {
            XmlPullParser xmlPullParser = Xml.newPullParser();
            xmlPullParser.setInput(new StringReader(new String(ruleBytes, StandardCharsets.UTF_8)));
            return parseRules(xmlPullParser);
        } catch (Exception e) {
            throw new RuleParseException(e.getMessage(), e);
        }
    }

    @Override
    public List<Rule> parse(InputStream inputStream, List<RuleIndexRange> indexRanges)
            throws RuleParseException {
        try {
            XmlPullParser xmlPullParser = Xml.newPullParser();
            xmlPullParser.setInput(inputStream, StandardCharsets.UTF_8.name());
            return parseRules(xmlPullParser);
        } catch (Exception e) {
            throw new RuleParseException(e.getMessage(), e);
        }
    }

    private static List<Rule> parseRules(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        List<Rule> rules = new ArrayList<>();

        // Skipping the first event type, which is always {@link XmlPullParser.START_DOCUMENT}
        parser.next();

        // Processing the first tag; which should always be a RuleList <RL> tag.
        String nodeName = parser.getName();
        // Validating that the XML is starting with a RuleList <RL> tag.
        // Note: This is the only breaking validation to run against XML files in the platform.
        // All rules inside are assumed to be validated at the server. If a rule is found to be
        // corrupt in the XML, it will be skipped to the next rule.
        if (!nodeName.equals(RULE_LIST_TAG)) {
            throw new RuntimeException(
                    String.format(
                            "Rules must start with RuleList <RL> tag. Found: %s at %s",
                            nodeName, parser.getPositionDescription()));
        }

        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            nodeName = parser.getName();
            if (eventType != XmlPullParser.START_TAG || !nodeName.equals(RULE_TAG)) {
                continue;
            }
            rules.add(parseRule(parser));
        }

        return rules;
    }

    private static Rule parseRule(XmlPullParser parser) throws IOException, XmlPullParserException {
        Formula formula = null;
        int effect = Integer.parseInt(extractAttributeValue(parser, EFFECT_ATTRIBUTE).orElse("-1"));

        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String nodeName = parser.getName();

            if (eventType == XmlPullParser.END_TAG && parser.getName().equals(RULE_TAG)) {
                break;
            }

            if (eventType == XmlPullParser.START_TAG) {
                switch (nodeName) {
                    case COMPOUND_FORMULA_TAG:
                        formula = parseCompoundFormula(parser);
                        break;
                    case ATOMIC_FORMULA_TAG:
                        formula = parseAtomicFormula(parser);
                        break;
                    default:
                        throw new RuntimeException(
                                String.format("Found unexpected tag: %s", nodeName));
                }
            } else {
                throw new RuntimeException(
                        String.format("Found unexpected event type: %d", eventType));
            }
        }

        return new Rule(formula, effect);
    }

    private static Formula parseCompoundFormula(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int connector =
                Integer.parseInt(extractAttributeValue(parser, CONNECTOR_ATTRIBUTE).orElse("-1"));
        List<Formula> formulas = new ArrayList<>();

        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String nodeName = parser.getName();

            if (eventType == XmlPullParser.END_TAG
                    && parser.getName().equals(COMPOUND_FORMULA_TAG)) {
                break;
            }

            if (eventType == XmlPullParser.START_TAG) {
                switch (nodeName) {
                    case ATOMIC_FORMULA_TAG:
                        formulas.add(parseAtomicFormula(parser));
                        break;
                    case COMPOUND_FORMULA_TAG:
                        formulas.add(parseCompoundFormula(parser));
                        break;
                    default:
                        throw new RuntimeException(
                                String.format("Found unexpected tag: %s", nodeName));
                }
            } else {
                throw new RuntimeException(
                        String.format("Found unexpected event type: %d", eventType));
            }
        }

        return new CompoundFormula(connector, formulas);
    }

    private static Formula parseAtomicFormula(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int key = Integer.parseInt(extractAttributeValue(parser, KEY_ATTRIBUTE).orElse("-1"));
        int operator =
                Integer.parseInt(extractAttributeValue(parser, OPERATOR_ATTRIBUTE).orElse("-1"));
        String value = extractAttributeValue(parser, VALUE_ATTRIBUTE).orElse(null);
        String isHashedValue =
                extractAttributeValue(parser, IS_HASHED_VALUE_ATTRIBUTE).orElse(null);

        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.END_TAG && parser.getName().equals(ATOMIC_FORMULA_TAG)) {
                break;
            }
        }
        return constructAtomicFormulaBasedOnKey(key, operator, value, isHashedValue);
    }

    private static Formula constructAtomicFormulaBasedOnKey(
            @AtomicFormula.Key int key,
            @AtomicFormula.Operator int operator,
            String value,
            String isHashedValue) {
        switch (key) {
            case AtomicFormula.PACKAGE_NAME:
            case AtomicFormula.INSTALLER_NAME:
            case AtomicFormula.APP_CERTIFICATE:
            case AtomicFormula.INSTALLER_CERTIFICATE:
                return new AtomicFormula.StringAtomicFormula(
                        key, value, Boolean.parseBoolean(isHashedValue));
            case AtomicFormula.PRE_INSTALLED:
                return new AtomicFormula.BooleanAtomicFormula(key, Boolean.parseBoolean(value));
            case AtomicFormula.VERSION_CODE:
                return new AtomicFormula.IntAtomicFormula(key, operator, Integer.parseInt(value));
            default:
                throw new RuntimeException(String.format("Found unexpected key: %d", key));
        }
    }

    private static Optional<String> extractAttributeValue(XmlPullParser parser, String attribute) {
        return Optional.ofNullable(parser.getAttributeValue(NAMESPACE, attribute));
    }
}
