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

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Rule;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class RuleXmlParserTest {

    @Test
    public void testXmlStream_validCompoundFormula() throws Exception {
        Map<String, String> atomicFormulaAttrs = new HashMap<>();
        atomicFormulaAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        atomicFormulaAttrs.put("V", "com.app.test");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", atomicFormulaAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        InputStream inputStream = new ByteArrayInputStream(ruleXmlCompoundFormula.getBytes());
        Rule expectedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(inputStream, Collections.emptyList());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validCompoundFormula_notConnector() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validCompoundFormula_andConnector() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        Map<String, String> appCertificateAttrs = new HashMap<>();
        appCertificateAttrs.put("K", String.valueOf(AtomicFormula.APP_CERTIFICATE));
        appCertificateAttrs.put("V", "test_cert");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.AND)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", appCertificateAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE,
                                                "test_cert",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        List<Rule> rules = xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validCompoundFormula_orConnector() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        Map<String, String> appCertificateAttrs = new HashMap<>();
        appCertificateAttrs.put("K", String.valueOf(AtomicFormula.APP_CERTIFICATE));
        appCertificateAttrs.put("V", "test_cert");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.OR)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", appCertificateAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.OR,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE,
                                                "test_cert",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validCompoundFormula_differentTagOrder() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_invalidCompoundFormula_invalidNumberOfFormulas() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        Map<String, String> versionCodeAttrs = new HashMap<>();
        versionCodeAttrs.put("K", String.valueOf(AtomicFormula.VERSION_CODE));
        versionCodeAttrs.put("O", String.valueOf(AtomicFormula.EQ));
        versionCodeAttrs.put("V", "1");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", versionCodeAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Connector NOT must have 1 formula only",
                () -> xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_invalidCompoundFormula_invalidOperator() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("O", "INVALID_OPERATOR");
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"INVALID_OPERATOR\"",
                () -> xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_invalidCompoundFormula_invalidEffect() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", "INVALID_EFFECT"),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"INVALID_EFFECT\"",
                () -> xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_invalidCompoundFormula_invalidTags() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "InvalidAtomicFormula",
                                packageNameAttrs,
                                /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Found unexpected tag: InvalidAtomicFormula",
                () -> xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_validAtomicFormula_stringValue() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlAtomicFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                "com.app.test",
                                /* isHashedValue= */ false),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_integerValue() throws Exception {
        Map<String, String> versionCodeAttrs = new HashMap<>();
        versionCodeAttrs.put("K", String.valueOf(AtomicFormula.VERSION_CODE));
        versionCodeAttrs.put("O", String.valueOf(AtomicFormula.EQ));
        versionCodeAttrs.put("V", "1");
        String ruleXmlAtomicFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", versionCodeAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.IntAtomicFormula(
                                AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_booleanValue() throws Exception {
        Map<String, String> preInstalledAttrs = new HashMap<>();
        preInstalledAttrs.put("K", String.valueOf(AtomicFormula.PRE_INSTALLED));
        preInstalledAttrs.put("V", "true");
        String ruleXmlAtomicFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", preInstalledAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_validAtomicFormula_differentAttributeOrder() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlAtomicFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                "com.app.test",
                                /* isHashedValue= */ false),
                        Rule.DENY);

        List<Rule> rules = xmlParser.parse(ruleXmlAtomicFormula.getBytes(StandardCharsets.UTF_8));

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testXmlString_invalidAtomicFormula_invalidAttribute() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("BadKey", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlAtomicFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Found unexpected key: -1",
                () -> xmlParser.parse(ruleXmlAtomicFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_invalidRule_invalidAttribute() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlAtomicFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("BadEffect", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Unknown effect: -1",
                () -> xmlParser.parse(ruleXmlAtomicFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_invalidCompoundFormula_invalidAttribute() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlCompoundFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap(
                                        "BadConnector", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();
        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Unknown connector: -1",
                () -> xmlParser.parse(ruleXmlCompoundFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_invalidAtomicFormula() throws Exception {
        Map<String, String> packageNameAttrs = new HashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.VERSION_CODE));
        packageNameAttrs.put("O", String.valueOf(AtomicFormula.EQ));
        packageNameAttrs.put("V", "com.app.test");
        String ruleXmlAtomicFormula =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "For input string: \"com.app.test\"",
                () -> xmlParser.parse(ruleXmlAtomicFormula.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlString_withNoRuleList() {
        Map<String, String> atomicFormulaAttrs = new HashMap<>();
        atomicFormulaAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        atomicFormulaAttrs.put("V", "com.app.test");
        String ruleXmlWithNoRuleList =
                generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", atomicFormulaAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with RuleList <RL> tag",
                () -> xmlParser.parse(ruleXmlWithNoRuleList.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testXmlStream_withNoRuleList() {
        Map<String, String> atomicFormulaAttrs = new HashMap<>();
        atomicFormulaAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        atomicFormulaAttrs.put("V", "com.app.test");
        String ruleXmlWithNoRuleList =
                generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "OF",
                                Collections.singletonMap("C", String.valueOf(CompoundFormula.NOT)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", atomicFormulaAttrs, /* closed= */ true)
                        + "</OF>"
                        + "</R>";
        InputStream inputStream = new ByteArrayInputStream(ruleXmlWithNoRuleList.getBytes());
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with RuleList <RL> tag",
                () -> xmlParser.parse(inputStream, Collections.emptyList()));
    }

    private String generateTagWithAttribute(
            String tag, Map<String, String> attributeValues, boolean closed) {
        StringBuilder res = new StringBuilder("<");
        res.append(tag);
        for (String attribute : attributeValues.keySet()) {
            res.append(" ");
            res.append(attribute);
            res.append("=\"");
            res.append(attributeValues.get(attribute));
            res.append("\"");
        }
        res.append(closed ? " />" : ">");
        return res.toString();
    }
}
