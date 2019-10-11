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

package com.android.server.integrity.engine;

import android.util.Slog;

import com.android.server.integrity.model.AppInstallMetadata;
import com.android.server.integrity.model.AtomicFormula;
import com.android.server.integrity.model.Formula;
import com.android.server.integrity.model.OpenFormula;
import com.android.server.integrity.model.Rule;

import java.util.List;

/**
 * A helper class for evaluating rules against app install metadata to find if there are matching
 * rules.
 */
final class RuleEvaluator {

    private static final String TAG = "RuleEvaluator";

    /**
     * Match the list of rules against an app install metadata.
     *
     * @param rules              The list of rules to evaluate.
     * @param appInstallMetadata Metadata of the app to be installed, and to evaluate the rules
     *                           against.
     * @return A rule matching the metadata. If there are multiple matching rules, returns any. If
     * no rules are matching, returns {@link Rule#EMPTY}.
     */
    static Rule evaluateRules(List<Rule> rules, AppInstallMetadata appInstallMetadata) {
        for (Rule rule : rules) {
            if (isMatch(rule, appInstallMetadata)) {
                return rule;
            }
        }
        return Rule.EMPTY;
    }

    /**
     * Match a rule against app install metadata.
     */
    private static boolean isMatch(Rule rule, AppInstallMetadata appInstallMetadata) {
        return isMatch(rule.getFormula(), appInstallMetadata);
    }

    private static boolean isMatch(Formula formula, AppInstallMetadata appInstallMetadata) {
        if (formula instanceof AtomicFormula) {
            AtomicFormula atomicFormula = (AtomicFormula) formula;
            switch (atomicFormula.getKey()) {
                case PACKAGE_NAME:
                    return atomicFormula.isMatch(appInstallMetadata.getPackageName());
                case APP_CERTIFICATE:
                    return atomicFormula.isMatch(appInstallMetadata.getAppCertificate());
                case INSTALLER_NAME:
                    return atomicFormula.isMatch(appInstallMetadata.getInstallerName());
                case INSTALLER_CERTIFICATE:
                    return atomicFormula.isMatch(appInstallMetadata.getInstallerCertificate());
                case VERSION_CODE:
                    return atomicFormula.isMatch(appInstallMetadata.getVersionCode());
                case PRE_INSTALLED:
                    return atomicFormula.isMatch(appInstallMetadata.isPreInstalled());
                default:
                    Slog.i(TAG, String.format("Returned no match for unknown key %s",
                            atomicFormula.getKey()));
                    return false;
            }
        } else if (formula instanceof OpenFormula) {
            OpenFormula openFormula = (OpenFormula) formula;
            // A rule is in disjunctive normal form, so there are no OR connectors.
            switch (openFormula.getConnector()) {
                case NOT:
                    // NOT connector has only 1 formula attached.
                    return !isMatch(openFormula.getFormulas().get(0), appInstallMetadata);
                case AND:
                    return openFormula.getFormulas().stream().allMatch(
                            subFormula -> isMatch(subFormula, appInstallMetadata));
                default:
                    Slog.i(TAG, String.format("Returned no match for unknown connector %s",
                            openFormula.getConnector()));
                    return false;
            }
        }

        return false;
    }
}
