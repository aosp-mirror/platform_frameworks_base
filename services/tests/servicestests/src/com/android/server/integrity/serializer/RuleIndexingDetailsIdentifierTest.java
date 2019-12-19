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

import static com.android.server.integrity.serializer.RuleIndexingDetails.APP_CERTIFICATE_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.NOT_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.PACKAGE_NAME_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetailsIdentifier.splitRulesIntoIndexBuckets;
import static com.android.server.testutils.TestUtils.assertExpectException;

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link RuleIndexingDetailsIdentifier}. */
@RunWith(JUnit4.class)
public class RuleIndexingDetailsIdentifierTest {

    private static final String SAMPLE_APP_CERTIFICATE = "testcert";
    private static final String SAMPLE_INSTALLER_NAME = "com.test.installer";
    private static final String SAMPLE_INSTALLER_CERTIFICATE = "installercert";
    private static final String SAMPLE_PACKAGE_NAME = "com.test.package";

    private static final AtomicFormula ATOMIC_FORMULA_WITH_PACKAGE_NAME =
            new AtomicFormula.StringAtomicFormula(
                    AtomicFormula.PACKAGE_NAME,
                    SAMPLE_PACKAGE_NAME,
                    /* isHashedValue= */ false);
    private static final AtomicFormula ATOMIC_FORMULA_WITH_APP_CERTIFICATE =
            new AtomicFormula.StringAtomicFormula(
                    AtomicFormula.APP_CERTIFICATE,
                    SAMPLE_APP_CERTIFICATE,
                    /* isHashedValue= */ false);
    private static final AtomicFormula ATOMIC_FORMULA_WITH_INSTALLER_NAME =
            new AtomicFormula.StringAtomicFormula(
                    AtomicFormula.INSTALLER_NAME,
                    SAMPLE_INSTALLER_NAME,
                    /* isHashedValue= */ false);
    private static final AtomicFormula ATOMIC_FORMULA_WITH_INSTALLER_CERTIFICATE =
            new AtomicFormula.StringAtomicFormula(
                    AtomicFormula.INSTALLER_CERTIFICATE,
                    SAMPLE_INSTALLER_CERTIFICATE,
                    /* isHashedValue= */ false);
    private static final AtomicFormula ATOMIC_FORMULA_WITH_VERSION_CODE =
            new AtomicFormula.IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 12);
    private static final AtomicFormula ATOMIC_FORMULA_WITH_ISPREINSTALLED =
            new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, /* booleanValue= */
                    true);


    private static final Rule RULE_WITH_PACKAGE_NAME =
            new Rule(
                    new CompoundFormula(
                            CompoundFormula.AND,
                            Arrays.asList(
                                    ATOMIC_FORMULA_WITH_PACKAGE_NAME,
                                    ATOMIC_FORMULA_WITH_INSTALLER_NAME)),
                    Rule.DENY);
    private static final Rule RULE_WITH_APP_CERTIFICATE =
            new Rule(
                    new CompoundFormula(
                            CompoundFormula.AND,
                            Arrays.asList(
                                    ATOMIC_FORMULA_WITH_APP_CERTIFICATE,
                                    ATOMIC_FORMULA_WITH_INSTALLER_NAME)),
                    Rule.DENY);
    private static final Rule RULE_WITH_INSTALLER_RESTRICTIONS =
            new Rule(
                    new CompoundFormula(
                            CompoundFormula.AND,
                            Arrays.asList(
                                    ATOMIC_FORMULA_WITH_INSTALLER_NAME,
                                    ATOMIC_FORMULA_WITH_INSTALLER_CERTIFICATE)),
                    Rule.DENY);

    private static final Rule RULE_WITH_NONSTRING_RESTRICTIONS =
            new Rule(
                    new CompoundFormula(
                            CompoundFormula.AND,
                            Arrays.asList(
                                    ATOMIC_FORMULA_WITH_VERSION_CODE,
                                    ATOMIC_FORMULA_WITH_ISPREINSTALLED)),
                    Rule.DENY);

    @Test
    public void getIndexType_nullRule() {
        List<Rule> ruleList = null;

        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex= */
                "Index buckets cannot be created for null rule list.",
                () -> splitRulesIntoIndexBuckets(ruleList));
    }

    @Test
    public void getIndexType_invalidFormula() {
        List<Rule> ruleList = new ArrayList();
        ruleList.add(new Rule(getInvalidFormula(), Rule.DENY));

        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex= */ "Invalid formula tag type.",
                () -> splitRulesIntoIndexBuckets(ruleList));
    }

    @Test
    public void getIndexType_ruleContainingPackageNameFormula() {
        List<Rule> ruleList = new ArrayList();
        ruleList.add(RULE_WITH_PACKAGE_NAME);

        Map<Integer, List<Rule>> result = splitRulesIntoIndexBuckets(ruleList);

        // Verify the resulting map content.
        assertThat(result.keySet())
                .containsExactly(NOT_INDEXED, PACKAGE_NAME_INDEXED, APP_CERTIFICATE_INDEXED);
        assertThat(result.get(NOT_INDEXED)).isEmpty();
        assertThat(result.get(APP_CERTIFICATE_INDEXED)).isEmpty();
        assertThat(result.get(PACKAGE_NAME_INDEXED)).containsExactly(RULE_WITH_PACKAGE_NAME);
    }

    @Test
    public void getIndexType_ruleContainingAppCertificateFormula() {
        List<Rule> ruleList = new ArrayList();
        ruleList.add(RULE_WITH_APP_CERTIFICATE);

        Map<Integer, List<Rule>> result = splitRulesIntoIndexBuckets(ruleList);

        assertThat(result.keySet())
                .containsExactly(NOT_INDEXED, PACKAGE_NAME_INDEXED, APP_CERTIFICATE_INDEXED);
        assertThat(result.get(NOT_INDEXED)).isEmpty();
        assertThat(result.get(PACKAGE_NAME_INDEXED)).isEmpty();
        assertThat(result.get(APP_CERTIFICATE_INDEXED)).containsExactly(RULE_WITH_APP_CERTIFICATE);
    }

    @Test
    public void getIndexType_ruleWithUnindexedCompoundFormula() {
        List<Rule> ruleList = new ArrayList();
        ruleList.add(RULE_WITH_INSTALLER_RESTRICTIONS);

        Map<Integer, List<Rule>> result = splitRulesIntoIndexBuckets(ruleList);

        assertThat(result.keySet())
                .containsExactly(NOT_INDEXED, PACKAGE_NAME_INDEXED, APP_CERTIFICATE_INDEXED);
        assertThat(result.get(PACKAGE_NAME_INDEXED)).isEmpty();
        assertThat(result.get(APP_CERTIFICATE_INDEXED)).isEmpty();
        assertThat(result.get(NOT_INDEXED)).containsExactly(RULE_WITH_INSTALLER_RESTRICTIONS);
    }

    @Test
    public void getIndexType_ruleContainingCompoundFormulaWithIntAndBoolean() {
        List<Rule> ruleList = new ArrayList();
        ruleList.add(RULE_WITH_NONSTRING_RESTRICTIONS);

        Map<Integer, List<Rule>> result = splitRulesIntoIndexBuckets(ruleList);

        assertThat(result.keySet())
                .containsExactly(NOT_INDEXED, PACKAGE_NAME_INDEXED, APP_CERTIFICATE_INDEXED);
        assertThat(result.get(PACKAGE_NAME_INDEXED)).isEmpty();
        assertThat(result.get(APP_CERTIFICATE_INDEXED)).isEmpty();
        assertThat(result.get(NOT_INDEXED)).containsExactly(RULE_WITH_NONSTRING_RESTRICTIONS);
    }

    @Test
    public void getIndexType_negatedRuleContainingPackageNameFormula() {
        Rule negatedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Arrays.asList(
                                        new CompoundFormula(
                                                CompoundFormula.AND,
                                                Arrays.asList(
                                                        ATOMIC_FORMULA_WITH_PACKAGE_NAME,
                                                        ATOMIC_FORMULA_WITH_APP_CERTIFICATE)))),
                        Rule.DENY);
        List<Rule> ruleList = new ArrayList();
        ruleList.add(negatedRule);

        Map<Integer, List<Rule>> result = splitRulesIntoIndexBuckets(ruleList);

        assertThat(result.keySet())
                .containsExactly(NOT_INDEXED, PACKAGE_NAME_INDEXED, APP_CERTIFICATE_INDEXED);
        assertThat(result.get(PACKAGE_NAME_INDEXED)).isEmpty();
        assertThat(result.get(APP_CERTIFICATE_INDEXED)).isEmpty();
        assertThat(result.get(NOT_INDEXED)).containsExactly(negatedRule);
    }

    @Test
    public void getIndexType_allRulesTogetherInValidOrder() {
        Rule packageNameRuleA = getRuleWithPackageName("aaa");
        Rule packageNameRuleB = getRuleWithPackageName("bbb");
        Rule packageNameRuleC = getRuleWithPackageName("ccc");
        Rule certificateRule1 = getRuleWithAppCertificate("cert1");
        Rule certificateRule2 = getRuleWithAppCertificate("cert2");
        Rule certificateRule3 = getRuleWithAppCertificate("cert3");

        List<Rule> ruleList = new ArrayList();
        ruleList.add(packageNameRuleB);
        ruleList.add(packageNameRuleC);
        ruleList.add(packageNameRuleA);
        ruleList.add(certificateRule3);
        ruleList.add(certificateRule2);
        ruleList.add(certificateRule1);
        ruleList.add(RULE_WITH_INSTALLER_RESTRICTIONS);
        ruleList.add(RULE_WITH_NONSTRING_RESTRICTIONS);

        Map<Integer, List<Rule>> result = splitRulesIntoIndexBuckets(ruleList);

        assertThat(result.keySet())
                .containsExactly(NOT_INDEXED, PACKAGE_NAME_INDEXED, APP_CERTIFICATE_INDEXED);

        // We check asserts this way to ensure ordering based on package name.
        assertThat(result.get(PACKAGE_NAME_INDEXED).get(0)).isEqualTo(packageNameRuleA);
        assertThat(result.get(PACKAGE_NAME_INDEXED).get(1)).isEqualTo(packageNameRuleB);
        assertThat(result.get(PACKAGE_NAME_INDEXED).get(2)).isEqualTo(packageNameRuleC);

        // We check asserts this way to ensure ordering based on app certificate.
        assertThat(result.get(APP_CERTIFICATE_INDEXED).get(0)).isEqualTo(certificateRule1);
        assertThat(result.get(APP_CERTIFICATE_INDEXED).get(1)).isEqualTo(certificateRule2);
        assertThat(result.get(APP_CERTIFICATE_INDEXED).get(2)).isEqualTo(certificateRule3);

        assertThat(result.get(NOT_INDEXED))
                .containsExactly(
                        RULE_WITH_INSTALLER_RESTRICTIONS,
                        RULE_WITH_NONSTRING_RESTRICTIONS);
    }

    private Rule getRuleWithPackageName(String packageName) {
        return new Rule(
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.PACKAGE_NAME,
                                        packageName,
                                        /* isHashedValue= */ false),
                                ATOMIC_FORMULA_WITH_INSTALLER_NAME)),
                Rule.DENY);
    }

    private Rule getRuleWithAppCertificate(String certificate) {
        return new Rule(
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE,
                                        certificate,
                                        /* isHashedValue= */ false),
                                ATOMIC_FORMULA_WITH_INSTALLER_NAME)),
                Rule.DENY);
    }

    private Formula getInvalidFormula() {
        return new Formula() {
            @Override
            public boolean isSatisfied(AppInstallMetadata appInstallMetadata) {
                return false;
            }

            @Override
            public int getTag() {
                return 4;
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
