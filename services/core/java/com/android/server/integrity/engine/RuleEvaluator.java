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

import com.android.server.integrity.model.IntegrityCheckResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A helper class for evaluating rules against app install metadata to find if there are matching
 * rules.
 */
final class RuleEvaluator {

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

        // Identify the rules that match the {@code appInstallMetadata}.
        List<Rule> matchedRules =
                rules.stream()
                        .filter(rule -> rule.getFormula().matches(appInstallMetadata))
                        .collect(Collectors.toList());

        // Identify the matched power allow rules and terminate early if we have any.
        List<Rule> matchedPowerAllowRules =
                matchedRules.stream()
                        .filter(rule -> rule.getEffect() == FORCE_ALLOW)
                        .collect(Collectors.toList());

        if (!matchedPowerAllowRules.isEmpty()) {
            return IntegrityCheckResult.allow(matchedPowerAllowRules);
        }

        // Identify the matched deny rules.
        List<Rule> matchedDenyRules =
                matchedRules.stream()
                        .filter(rule -> rule.getEffect() == DENY)
                        .collect(Collectors.toList());

        if (!matchedDenyRules.isEmpty()) {
            return IntegrityCheckResult.deny(matchedDenyRules);
        }

        // When no rules are denied, return default allow result.
        return IntegrityCheckResult.allow();
    }
}
