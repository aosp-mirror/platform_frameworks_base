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

import java.util.Arrays;

/** Unit tests for {@link RuleIndexTypeIdentifier}. */
@RunWith(JUnit4.class)
public class RuleIndexTypeIdentifierTest {

    @Test
    public void getIndexType_nullRule() {
        Rule rule = null;

        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex= */
                "Indexing type cannot be determined for null rule.",
                () -> RuleIndexTypeIdentifier.getIndexType(rule));
    }

    @Test
    public void getIndexType_invalidFormula() {
        Rule rule = new Rule(getInvalidFormula(), Rule.DENY);

        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex= */ "Invalid formula tag type.",
                () -> RuleIndexTypeIdentifier.getIndexType(rule));
    }

    @Test
    public void getIndexType_ruleContainingPackageNameFormula() {
        String packageName = "com.test.app";
        String installerName = "com.test.installer";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                packageName,
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.INSTALLER_NAME,
                                                installerName,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        assertThat(RuleIndexTypeIdentifier.getIndexType(rule))
                .isEqualTo(RuleIndexTypeIdentifier.PACKAGE_NAME_INDEXED);
    }

    @Test
    public void getIndexType_ruleContainingAppCertificateFormula() {
        String appCertificate = "cert1";
        String installerName = "com.test.installer";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE,
                                                appCertificate,
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.INSTALLER_NAME,
                                                installerName,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        assertThat(RuleIndexTypeIdentifier.getIndexType(rule))
                .isEqualTo(RuleIndexTypeIdentifier.APP_CERTIFICATE_INDEXED);
    }

    @Test
    public void getIndexType_ruleWithUnindexedCompoundFormula() {
        String installerCertificate = "cert1";
        String installerName = "com.test.installer";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.INSTALLER_CERTIFICATE,
                                                installerCertificate,
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.INSTALLER_NAME,
                                                installerName,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        assertThat(RuleIndexTypeIdentifier.getIndexType(rule))
                .isEqualTo(RuleIndexTypeIdentifier.NOT_INDEXED);
    }

    @Test
    public void getIndexType_rulContainingCompoundFormulaWithIntAndBoolean() {
        int appVersion = 12;
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.BooleanAtomicFormula(
                                                AtomicFormula.PRE_INSTALLED,
                                                /* booleanValue= */ true),
                                        new AtomicFormula.IntAtomicFormula(
                                                AtomicFormula.VERSION_CODE,
                                                AtomicFormula.EQ,
                                                appVersion))),
                        Rule.DENY);

        assertThat(RuleIndexTypeIdentifier.getIndexType(rule))
                .isEqualTo(RuleIndexTypeIdentifier.NOT_INDEXED);
    }

    @Test
    public void getIndexType_negatedRuleContainingPackageNameFormula() {
        String packageName = "com.test.app";
        String installerName = "com.test.installer";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Arrays.asList(
                                        new CompoundFormula(
                                                CompoundFormula.AND,
                                                Arrays.asList(
                                                        new AtomicFormula.StringAtomicFormula(
                                                                AtomicFormula.PACKAGE_NAME,
                                                                packageName,
                                                                /* isHashedValue= */ false),
                                                        new AtomicFormula.StringAtomicFormula(
                                                                AtomicFormula.INSTALLER_NAME,
                                                                installerName,
                                                                /* isHashedValue= */ false))))),
                        Rule.DENY);

        assertThat(RuleIndexTypeIdentifier.getIndexType(rule))
                .isEqualTo(RuleIndexTypeIdentifier.NOT_INDEXED);
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

