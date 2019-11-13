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
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
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
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(new OpenFormula(OpenFormula.NOT, Collections.singletonList(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"))),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlOpenFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validOpenFormula_andConnector() throws Exception {
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.AND + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "<AF>"
                + "<K>" + AtomicFormula.APP_CERTIFICATE + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>test_cert</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
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
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.OR + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "<AF>"
                + "<K>" + AtomicFormula.APP_CERTIFICATE + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>test_cert</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
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
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(new OpenFormula(OpenFormula.NOT, Collections.singletonList(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"))),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlOpenFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidNumberOfFormulas() throws Exception {
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "<AF>"
                + "<K>" + AtomicFormula.VERSION_CODE + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>1</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Connector NOT must have 1 formula only",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidOperator() throws Exception {
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>INVALID_OPERATOR</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"INVALID_OPERATOR\"",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidEffect() throws Exception {
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "</OF>"
                + "<E>INVALID_EFFECT</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"INVALID_EFFECT\"",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_invalidOpenFormula_invalidTags() throws Exception {
        String ruleXmlOpenFormula = "<RL>"
                + "<R>"
                + "<OF>"
                + "<InvalidConnector>" + OpenFormula.NOT + "</InvalidConnector>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Found unexpected tag: InvalidConnector",
                () -> xmlParser.parse(ruleXmlOpenFormula));
    }

    @Test
    public void testXmlString_validAtomicFormula_stringValue() throws Exception {
        String ruleXmlAtomicFormula = "<RL>"
                + "<R>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_integerValue() throws Exception {
        String ruleXmlAtomicFormula = "<RL>"
                + "<R>"
                + "<AF>"
                + "<K>" + AtomicFormula.VERSION_CODE + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>1</V>"
                + "</AF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_booleanValue() throws Exception {
        String ruleXmlAtomicFormula = "<RL>"
                + "<R>"
                + "<AF>"
                + "<K>" + AtomicFormula.PRE_INSTALLED + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>true</V>"
                + "</AF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_differentTagOrder() throws Exception {
        String ruleXmlAtomicFormula = "<RL>"
                + "<R>"
                + "<AF>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "</AF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule = new Rule(
                new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "com.app.test"),
                Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_invalidAtomicFormula_invalidTags() throws Exception {
        String ruleXmlAtomicFormula = "<RL>"
                + "<R>"
                + "<AF>"
                + "<BadKey>" + AtomicFormula.PACKAGE_NAME + "</BadKey>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Found unexpected tag: BadKey",
                () -> xmlParser.parse(ruleXmlAtomicFormula));
    }

    @Test
    public void testXmlString_invalidAtomicFormula() throws Exception {
        String ruleXmlAtomicFormula = "<RL>"
                + "<R>"
                + "<AF>"
                + "<K>" + AtomicFormula.VERSION_CODE + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>"
                + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"com.app.test\"",
                () -> xmlParser.parse(ruleXmlAtomicFormula));
    }

    @Test
    public void testXmlString_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with RuleList <RL> tag",
                () -> xmlParser.parse(ruleXmlWithNoRuleList));
    }

    @Test
    public void testXmlStream_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<R>"
                + "<OF>"
                + "<C>" + OpenFormula.NOT + "</C>"
                + "<AF>"
                + "<K>" + AtomicFormula.PACKAGE_NAME + "</K>"
                + "<O>" + AtomicFormula.EQ + "</O>"
                + "<V>com.app.test</V>"
                + "</AF>"
                + "</OF>"
                + "<E>" + Rule.DENY + "</E>"
                + "</R>";
        InputStream inputStream = new ByteArrayInputStream(ruleXmlWithNoRuleList.getBytes());
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with RuleList <RL> tag",
                () -> xmlParser.parse(inputStream));
    }
}
