/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.integrity.model;

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Rule;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

@RunWith(JUnit4.class)
public class IntegrityCheckResultTest {

    @Test
    public void createAllowResult() {
        IntegrityCheckResult allowResult = IntegrityCheckResult.allow();

        assertThat(allowResult.getEffect()).isEqualTo(IntegrityCheckResult.Effect.ALLOW);
        assertThat(allowResult.getMatchedRules()).isEmpty();
        assertThat(allowResult.getLoggingResponse())
                .isEqualTo(FrameworkStatsLog.INTEGRITY_CHECK_RESULT_REPORTED__RESPONSE__ALLOWED);
    }

    @Test
    public void createAllowResultWithRule() {
        String packageName = "com.test.deny";
        Rule forceAllowRule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME,
                                packageName),
                        Rule.FORCE_ALLOW);

        IntegrityCheckResult allowResult =
                IntegrityCheckResult.allow(Collections.singletonList(forceAllowRule));

        assertThat(allowResult.getEffect()).isEqualTo(IntegrityCheckResult.Effect.ALLOW);
        assertThat(allowResult.getMatchedRules()).containsExactly(forceAllowRule);
        assertThat(allowResult.getLoggingResponse())
                .isEqualTo(
                        FrameworkStatsLog.INTEGRITY_CHECK_RESULT_REPORTED__RESPONSE__FORCE_ALLOWED);
    }

    @Test
    public void createDenyResultWithRule() {
        String packageName = "com.test.deny";
        Rule failedRule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME,
                                packageName),
                        Rule.DENY);

        IntegrityCheckResult denyResult =
                IntegrityCheckResult.deny(Collections.singletonList(failedRule));

        assertThat(denyResult.getEffect()).isEqualTo(IntegrityCheckResult.Effect.DENY);
        assertThat(denyResult.getMatchedRules()).containsExactly(failedRule);
        assertThat(denyResult.getLoggingResponse())
                .isEqualTo(FrameworkStatsLog.INTEGRITY_CHECK_RESULT_REPORTED__RESPONSE__REJECTED);
    }

    @Test
    public void isDenyCausedByAppCertificate() {
        String packageName = "com.test.deny";
        String appCert = "app-cert";
        Rule failedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME, packageName),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE, appCert))),
                        Rule.DENY);
        Rule otherFailedRule =
                new Rule(
                        new AtomicFormula.LongAtomicFormula(AtomicFormula.VERSION_CODE,
                                AtomicFormula.EQ, 12),
                        Rule.DENY);

        IntegrityCheckResult denyResult =
                IntegrityCheckResult.deny(Arrays.asList(failedRule, otherFailedRule));

        assertThat(denyResult.isCausedByAppCertRule()).isTrue();
        assertThat(denyResult.isCausedByInstallerRule()).isFalse();
    }

    @Test
    public void isDenyCausedByInstaller() {
        String packageName = "com.test.deny";
        String appCert = "app-cert";
        Rule failedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME, packageName),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.INSTALLER_CERTIFICATE, appCert))),
                        Rule.DENY);
        Rule otherFailedRule =
                new Rule(
                        new AtomicFormula.LongAtomicFormula(AtomicFormula.VERSION_CODE,
                                AtomicFormula.EQ, 12),
                        Rule.DENY);

        IntegrityCheckResult denyResult =
                IntegrityCheckResult.deny(Arrays.asList(failedRule, otherFailedRule));

        assertThat(denyResult.isCausedByAppCertRule()).isFalse();
        assertThat(denyResult.isCausedByInstallerRule()).isTrue();
    }
}
