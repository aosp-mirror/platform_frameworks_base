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

import java.util.ArrayList;
import java.util.List;

/**
 * The engine used to evaluate rules against app installs.
 *
 * <p>Every app install is evaluated against rules (pushed by the verifier) by the evaluation engine
 * to allow/block that install.
 */
public final class RuleEvaluationEngine {
    private static final String TAG = "RuleEvaluation";

    // The engine for loading rules, retrieving metadata for app installs, and evaluating app
    // installs against rules.
    private static RuleEvaluationEngine sRuleEvaluationEngine;

    // The subset of rules loaded to be used to evaluate an app install request.
    // TODO: Load rules relevant to app installs.
    private List<String> mRules;

    private RuleEvaluationEngine() {
        // Initialize rules with the empty rule set.
        mRules = new ArrayList<>();
    }

    /**
     * Provide a singleton instance of the rule evaluation engine.
     */
    public static synchronized RuleEvaluationEngine getRuleEvaluationEngine() {
        if (sRuleEvaluationEngine == null) {
            return new RuleEvaluationEngine();
        }
        return sRuleEvaluationEngine;
    }
}
