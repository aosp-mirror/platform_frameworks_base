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
import com.android.server.integrity.model.IntegrityCheckResult;
import com.android.server.integrity.model.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * The engine used to evaluate rules against app installs.
 *
 * <p>Every app install is evaluated against rules (pushed by the verifier) by the evaluation
 * engine to allow/block that install.
 */
public final class RuleEvaluationEngine {
    private static final String TAG = "RuleEvaluation";

    // The engine for loading rules, retrieving metadata for app installs, and evaluating app
    // installs against rules.
    private static RuleEvaluationEngine sRuleEvaluationEngine;

    /**
     * Provide a singleton instance of the rule evaluation engine.
     */
    public static synchronized RuleEvaluationEngine getRuleEvaluationEngine() {
        if (sRuleEvaluationEngine == null) {
            return new RuleEvaluationEngine();
        }
        return sRuleEvaluationEngine;
    }

    /**
     * Load, and match the list of rules against an app install metadata.
     *
     * @param appInstallMetadata Metadata of the app to be installed, and to evaluate the rules
     *                           against.
     * @return A rule matching the metadata. If there are multiple matching rules, returns any. If
     * no rules are matching, returns {@link Rule#EMPTY}.
     */
    public IntegrityCheckResult evaluate(AppInstallMetadata appInstallMetadata) {
        List<Rule> rules = loadRules(appInstallMetadata);
        Rule matchedRule = RuleEvaluator.evaluateRules(rules, appInstallMetadata);
        if (matchedRule == Rule.EMPTY) {
            return IntegrityCheckResult.allow();
        } else {
            switch (matchedRule.getEffect()) {
                case DENY:
                    return IntegrityCheckResult.deny(matchedRule);
                default:
                    Slog.e(TAG, "Matched a non-DENY rule: " + matchedRule);
                    return IntegrityCheckResult.allow();
            }
        }
    }

    private List<Rule> loadRules(AppInstallMetadata appInstallMetadata) {
        // TODO: Load rules
        return new ArrayList<>();
    }
}
