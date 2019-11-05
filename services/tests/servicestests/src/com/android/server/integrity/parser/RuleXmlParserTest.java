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

import static com.android.server.testutils.TestUtils.assertExpectException;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.integrity.model.AtomicFormula;
import com.android.server.integrity.model.OpenFormula;
import com.android.server.integrity.model.Rule;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class RuleXmlParserTest {

    private static final String VALID_RULE_XML = "<RuleList>"
            + "<Rule>"
            + "<OpenFormula>"
            + "<Connector>" + OpenFormula.NOT + "</Connector>"
            + "<AtomicFormula>"
            + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
            + "<Operator>" + AtomicFormula.EQ + "</Operator>"
            + "<Value>com.app.test</Value>"
            + "</AtomicFormula>"
            + "</OpenFormula>"
            + "<Effect>" + Rule.DENY + "</Effect>"
            + "</Rule>"
            + "</RuleList>";

    @Test
    public void testXmlString_validRule() {
        RuleParser xmlParser = new RuleXmlParser();

        List<Rule> rules = xmlParser.parse(VALID_RULE_XML);

        assertThat(rules).isEmpty();
    }

    @Test
    public void testXmlStream_validRule() {
        RuleParser xmlParser = new RuleXmlParser();
        InputStream inputStream = new ByteArrayInputStream(VALID_RULE_XML.getBytes());

        List<Rule> rules = xmlParser.parse(inputStream);

        assertThat(rules).isEmpty();
    }

    @Test
    public void testXmlString_validAtomicFormula_stringValue() {
        String ruleXmlAtomicFormula = "<RuleList>"
                + "<Rule>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_integerValue() {
        String ruleXmlAtomicFormula = "<RuleList>"
                + "<Rule>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.VERSION_CODE + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>1</Value>"
                + "</AtomicFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_booleanValue() {
        String ruleXmlAtomicFormula = "<RuleList>"
                + "<Rule>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PRE_INSTALLED + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>true</Value>"
                + "</AtomicFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_differentTagOrder() {
        String ruleXmlAtomicFormula = "<RuleList>"
                + "<Rule>"
                + "<AtomicFormula>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "</AtomicFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_invalidAtomicFormula_invalidTags() {
        String ruleXmlAtomicFormula = "<RuleList>"
                + "<Rule>"
                + "<AtomicFormula>"
                + "<BadKey>" + AtomicFormula.PACKAGE_NAME + "</BadKey>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEmpty();
    }

    @Test
    public void testXmlString_invalidAtomicFormula() {
        String ruleXmlAtomicFormula = "<RuleList>"
                + "<Rule>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.VERSION_CODE + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEmpty();
    }

    @Test
    public void testXmlString_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<Rule>"
                + "<OpenFormula>"
                + "<Connector>NOT</Connector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuntimeException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with <RuleList> tag.",
                () -> xmlParser.parse(ruleXmlWithNoRuleList));
    }

    @Test
    public void testXmlStream_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<Rule>"
                + "<OpenFormula>"
                + "<Connector>NOT</Connector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>";
        InputStream inputStream = new ByteArrayInputStream(ruleXmlWithNoRuleList.getBytes());
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuntimeException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with <RuleList> tag.",
                () -> xmlParser.parse(inputStream));
    }
}
