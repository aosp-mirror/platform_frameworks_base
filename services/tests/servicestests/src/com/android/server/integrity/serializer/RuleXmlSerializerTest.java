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

import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.junit.Assert.assertEquals;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RunWith(JUnit4.class)
public class RuleXmlSerializerTest {

    private static final String SAMPLE_INSTALLER_NAME = "com.test.installer";
    private static final String SAMPLE_INSTALLER_CERT = "installer_cert";

    @Test
    public void testXmlString_serializeEmptyRuleList() throws Exception {
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        String expectedRules = "<RL />";

        byte[] actualRules =
                xmlSerializer.serialize(
                        Collections.emptyList(), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeMultipleRules_indexingOrderPreserved() throws Exception {
        String packageNameA = "aaa";
        String packageNameB = "bbb";
        String packageNameC = "ccc";
        String appCert1 = "cert1";
        String appCert2 = "cert2";
        String appCert3 = "cert3";
        Rule installerRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.INSTALLER_NAME,
                                                SAMPLE_INSTALLER_NAME,
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.INSTALLER_CERTIFICATE,
                                                SAMPLE_INSTALLER_CERT,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        byte[] actualRules =
                xmlSerializer.serialize(
                        Arrays.asList(
                                installerRule,
                                getRuleWithAppCertificateAndSampleInstallerName(appCert1),
                                getRuleWithPackageNameAndSampleInstallerName(packageNameB),
                                getRuleWithAppCertificateAndSampleInstallerName(appCert3),
                                getRuleWithPackageNameAndSampleInstallerName(packageNameC),
                                getRuleWithAppCertificateAndSampleInstallerName(appCert2),
                                getRuleWithPackageNameAndSampleInstallerName(packageNameA)),
                        /* formatVersion= */ Optional.empty());

        String expectedRules = "<RL>"
                + getSerializedCompoundRuleWithPackageNameAndSampleInstallerName(packageNameA)
                + getSerializedCompoundRuleWithPackageNameAndSampleInstallerName(packageNameB)
                + getSerializedCompoundRuleWithPackageNameAndSampleInstallerName(packageNameC)
                + getSerializedCompoundRuleWithAppCertificateAndSampleInstallerName(appCert1)
                + getSerializedCompoundRuleWithAppCertificateAndSampleInstallerName(appCert2)
                + getSerializedCompoundRuleWithAppCertificateAndSampleInstallerName(appCert3)
                + getSerializedCompoundRuleWithSampleInstallerNameAndCert()
                + "</RL>";

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlStream_serializeValidCompoundFormula() throws Exception {
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        OutputStream outputStream = new ByteArrayOutputStream();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        String expectedRules =
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

        xmlSerializer.serialize(
                Collections.singletonList(rule),
                /* formatVersion= */ Optional.empty(),
                outputStream,
                new ByteArrayOutputStream());

        byte[] actualRules = outputStream.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeValidCompoundFormula_notConnector() throws Exception {
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "com.app.test",
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        String expectedRules =
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

        byte[] actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeValidCompoundFormula_andConnector() throws Exception {
        Rule rule =
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
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        Map<String, String> appCertificateAttrs = new LinkedHashMap<>();
        appCertificateAttrs.put("K", String.valueOf(AtomicFormula.APP_CERTIFICATE));
        appCertificateAttrs.put("V", "test_cert");
        appCertificateAttrs.put("H", "false");
        String expectedRules =
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

        byte[] actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeValidCompoundFormula_orConnector() throws Exception {
        Rule rule =
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
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        Map<String, String> appCertificateAttrs = new LinkedHashMap<>();
        appCertificateAttrs.put("K", String.valueOf(AtomicFormula.APP_CERTIFICATE));
        appCertificateAttrs.put("V", "test_cert");
        appCertificateAttrs.put("H", "false");
        String expectedRules =
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

        byte[] actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeValidAtomicFormula_stringValue() throws Exception {
        Rule rule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                "com.app.test",
                                /* isHashedValue= */ false),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", "com.app.test");
        packageNameAttrs.put("H", "false");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", packageNameAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";

        byte[] actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeValidAtomicFormula_integerValue() throws Exception {
        Rule rule =
                new Rule(
                        new AtomicFormula.IntAtomicFormula(
                                AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> versionCodeAttrs = new LinkedHashMap<>();
        versionCodeAttrs.put("K", String.valueOf(AtomicFormula.VERSION_CODE));
        versionCodeAttrs.put("O", String.valueOf(AtomicFormula.EQ));
        versionCodeAttrs.put("V", "1");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", versionCodeAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";

        byte[] actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeValidAtomicFormula_booleanValue() throws Exception {
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();
        Map<String, String> preInstalledAttrs = new LinkedHashMap<>();
        preInstalledAttrs.put("K", String.valueOf(AtomicFormula.PRE_INSTALLED));
        preInstalledAttrs.put("V", "true");
        String expectedRules =
                "<RL>"
                        + generateTagWithAttribute(
                                /* tag= */ "R",
                                Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                                /* closed= */ false)
                        + generateTagWithAttribute(
                                /* tag= */ "AF", preInstalledAttrs, /* closed= */ true)
                        + "</R>"
                        + "</RL>";

        byte[] actualRules =
                xmlSerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertEquals(expectedRules, new String(actualRules, StandardCharsets.UTF_8));
    }

    @Test
    public void testXmlString_serializeInvalidFormulaType() throws Exception {
        Formula invalidFormula = getInvalidFormula();
        Rule rule = new Rule(invalidFormula, Rule.DENY);
        RuleSerializer xmlSerializer = new RuleXmlSerializer();

        assertExpectException(
                RuleSerializeException.class,
                /* expectedExceptionMessageRegex */ "Malformed rule identified.",
                () ->
                        xmlSerializer.serialize(
                                Collections.singletonList(rule),
                                /* formatVersion= */ Optional.empty()));
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

    private Rule getRuleWithPackageNameAndSampleInstallerName(String packageName) {
        return new Rule(
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.PACKAGE_NAME,
                                        packageName,
                                        /* isHashedValue= */ false),
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.INSTALLER_NAME,
                                        SAMPLE_INSTALLER_NAME,
                                        /* isHashedValue= */ false))),
                Rule.DENY);
    }

    private String getSerializedCompoundRuleWithPackageNameAndSampleInstallerName(
            String packageName) {

        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.PACKAGE_NAME));
        packageNameAttrs.put("V", packageName);
        packageNameAttrs.put("H", "false");

        Map<String, String> installerNameAttrs = new LinkedHashMap<>();
        installerNameAttrs.put("K", String.valueOf(AtomicFormula.INSTALLER_NAME));
        installerNameAttrs.put("V", SAMPLE_INSTALLER_NAME);
        installerNameAttrs.put("H", "false");

        return generateTagWithAttribute(
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
                        /* tag= */ "AF", installerNameAttrs, /* closed= */ true)
                + "</OF>"
                + "</R>";
    }


    private Rule getRuleWithAppCertificateAndSampleInstallerName(String certificate) {
        return new Rule(
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE,
                                        certificate,
                                        /* isHashedValue= */ false),
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.INSTALLER_NAME,
                                        SAMPLE_INSTALLER_NAME,
                                        /* isHashedValue= */ false))),
                Rule.DENY);
    }

    private String getSerializedCompoundRuleWithAppCertificateAndSampleInstallerName(
            String appCert) {

        Map<String, String> packageNameAttrs = new LinkedHashMap<>();
        packageNameAttrs.put("K", String.valueOf(AtomicFormula.APP_CERTIFICATE));
        packageNameAttrs.put("V", appCert);
        packageNameAttrs.put("H", "false");

        Map<String, String> installerNameAttrs = new LinkedHashMap<>();
        installerNameAttrs.put("K", String.valueOf(AtomicFormula.INSTALLER_NAME));
        installerNameAttrs.put("V", SAMPLE_INSTALLER_NAME);
        installerNameAttrs.put("H", "false");

        return generateTagWithAttribute(
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
                        /* tag= */ "AF", installerNameAttrs, /* closed= */ true)
                + "</OF>"
                + "</R>";
    }

    private String getSerializedCompoundRuleWithSampleInstallerNameAndCert() {
        Map<String, String> installerNameAttrs = new LinkedHashMap<>();
        installerNameAttrs.put("K", String.valueOf(AtomicFormula.INSTALLER_NAME));
        installerNameAttrs.put("V", SAMPLE_INSTALLER_NAME);
        installerNameAttrs.put("H", "false");

        Map<String, String> installerCertAttrs = new LinkedHashMap<>();
        installerCertAttrs.put("K", String.valueOf(AtomicFormula.INSTALLER_CERTIFICATE));
        installerCertAttrs.put("V", SAMPLE_INSTALLER_CERT);
        installerCertAttrs.put("H", "false");

        return generateTagWithAttribute(
                        /* tag= */ "R",
                        Collections.singletonMap("E", String.valueOf(Rule.DENY)),
                        /* closed= */ false)
                + generateTagWithAttribute(
                        /* tag= */ "OF",
                        Collections.singletonMap("C", String.valueOf(CompoundFormula.AND)),
                        /* closed= */ false)
                + generateTagWithAttribute(
                        /* tag= */ "AF", installerNameAttrs, /* closed= */ true)
                + generateTagWithAttribute(
                        /* tag= */ "AF", installerCertAttrs, /* closed= */ true)
                + "</OF>"
                + "</R>";
    }

    private Formula getInvalidFormula() {
        return new Formula() {
            @Override
            public boolean isSatisfied(AppInstallMetadata appInstallMetadata) {
                return false;
            }

            @Override
            public int getTag() {
                return 0;
            }

            @Override
            public int hashCode() {
                return super.hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                return super.equals(obj);
            }

            @NonNull
            @Override
            protected Object clone() throws CloneNotSupportedException {
                return super.clone();
            }

            @Override
            public String toString() {
                return super.toString();
            }

            @Override
            protected void finalize() throws Throwable {
                super.finalize();
            }
        };
    }
}
