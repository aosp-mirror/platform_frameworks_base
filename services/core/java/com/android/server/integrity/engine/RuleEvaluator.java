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

import com.android.server.integrity.model.AppInstallMetadata;
import com.android.server.integrity.model.Rule;

import java.util.List;

/**
 * A helper class for evaluating rules against app install metadata to find if there are matching
 * rules.
 */
final class RuleEvaluator {

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

    private static boolean isMatch(Rule rule, AppInstallMetadata appInstallMetadata) {
        // TODO: Add matching logic
        return false;
    }
}
