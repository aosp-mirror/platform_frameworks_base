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

import static android.content.integrity.Rule.DENY;
import static android.content.integrity.Rule.FORCE_ALLOW;

import android.annotation.NonNull;
import android.content.integrity.AppInstallMetadata;
import android.content.integrity.Rule;
import android.util.Slog;

import com.android.server.integrity.model.IntegrityCheckResult;

import java.util.ArrayList;
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
     * <p>Rules must be in disjunctive normal form (DNF). A rule should contain AND'ed formulas
     * only. All rules are OR'ed together by default.
     *
     * @param rules The list of rules to evaluate.
     * @param appInstallMetadata Metadata of the app to be installed, and to evaluate the rules
     *     against.
     * @return result of the integrity check
     */
    @NonNull
    static IntegrityCheckResult evaluateRules(
            List<Rule> rules, AppInstallMetadata appInstallMetadata) {
        List<Rule> matchedRules = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.getFormula().isSatisfied(appInstallMetadata)) {
                matchedRules.add(rule);
            }
        }

        boolean denied = false;
        Rule denyRule = null;
        for (Rule rule : matchedRules) {
            switch (rule.getEffect()) {
                case DENY:
                    if (!denied) {
                        denied = true;
                        denyRule = rule;
                    }
                    break;
                case FORCE_ALLOW:
                    return IntegrityCheckResult.allow(rule);
                default:
                    Slog.e(TAG, "Matched an unknown effect rule: " + rule);
                    return IntegrityCheckResult.allow();
            }
        }
        return denied ? IntegrityCheckResult.deny(denyRule) : IntegrityCheckResult.allow();
    }
}
