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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class RuleXmlParserTest {

    @Test
    public void testXmlStream_validOpenFormula() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
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
        RuleParser xmlParser = new RuleXmlParser();
        InputStream inputStream = new ByteArrayInputStream(ruleXmlOpenFormula.getBytes());
        Rule expectedRule = new Rule(new OpenFormula(OpenFormula.NOT, Collections.singletonList(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"))),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(inputStream);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validOpenFormula_notConnector() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
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
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(new OpenFormula(OpenFormula.NOT, Collections.singletonList(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"))),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlOpenFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validOpenFormula_andConnector() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
                + "<Rule>"
                + "<OpenFormula>"
                + "<Connector>" + OpenFormula.AND + "</Connector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.APP_CERTIFICATE + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>test_cert</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(new OpenFormula(OpenFormula.AND, Arrays.asList(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"),
                new AtomicFormula.StringAtomicFormula(AtomicFormula.APP_CERTIFICATE, "test_cert"))),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlOpenFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validOpenFormula_orConnector() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
                + "<Rule>"
                + "<OpenFormula>"
                + "<Connector>" + OpenFormula.OR + "</Connector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.APP_CERTIFICATE + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>test_cert</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(new OpenFormula(OpenFormula.OR, Arrays.asList(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"),
                new AtomicFormula.StringAtomicFormula(AtomicFormula.APP_CERTIFICATE, "test_cert"))),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlOpenFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validOpenFormula_differentTagOrder() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
                + "<Rule>"
                + "<OpenFormula>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "<Connector>" + OpenFormula.NOT + "</Connector>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(new OpenFormula(OpenFormula.NOT, Collections.singletonList(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"))),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlOpenFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidNumberOfFormulas() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
                + "<Rule>"
                + "<OpenFormula>"
                + "<Connector>" + OpenFormula.NOT + "</Connector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.VERSION_CODE + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>1</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Connector NOT must have 1 formula only",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidOperator() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
                + "<Rule>"
                + "<OpenFormula>"
                + "<Connector>" + OpenFormula.NOT + "</Connector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>INVALID_OPERATOR</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"INVALID_OPERATOR\"",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidEffect() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
                + "<Rule>"
                + "<OpenFormula>"
                + "<Connector>" + OpenFormula.NOT + "</Connector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>INVALID_EFFECT</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"INVALID_EFFECT\"",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidTags() throws Exception {
        String ruleXmlOpenFormula = "<RuleList>"
                + "<Rule>"
                + "<OpenFormula>"
                + "<InvalidConnector>" + OpenFormula.NOT + "</InvalidConnector>"
                + "<AtomicFormula>"
                + "<Key>" + AtomicFormula.PACKAGE_NAME + "</Key>"
                + "<Operator>" + AtomicFormula.EQ + "</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>" + Rule.DENY + "</Effect>"
                + "</Rule>"
                + "</RuleList>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Found unexpected tag: InvalidConnector",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_validAtomicFormula_stringValue() throws Exception {
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
    public void testXmlString_validAtomicFormula_integerValue() throws Exception {
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
    public void testXmlString_validAtomicFormula_booleanValue() throws Exception {
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
    public void testXmlString_validAtomicFormula_differentTagOrder() throws Exception {
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
    public void testXmlString_invalidAtomicFormula_invalidTags() throws Exception {
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

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Found unexpected tag: BadKey",
                () -> xmlParser.parse(ruleXmlAtomicFormula));
    }

    @Test
    public void testXmlString_invalidAtomicFormula() throws Exception {
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

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"com.app.test\"",
                () -> xmlParser.parse(ruleXmlAtomicFormula));
    }

    @Test
    public void testXmlString_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<Rule>"
                + "<OpenFormula>"
                + "<Connector>" + OpenFormula.NOT + "</Connector>"
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
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with <RuleList> tag.",
                () -> xmlParser.parse(ruleXmlWithNoRuleList));
    }

    @Test
    public void testXmlStream_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<Rule>"
                + "<OpenFormula>"
                + "<Connector>" + OpenFormula.NOT + "</Connector>"
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
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with <RuleList> tag.",
                () -> xmlParser.parse(inputStream));
    }
}
