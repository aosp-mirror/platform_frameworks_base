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

package com.android.server.integrity.model;

import android.annotation.Nullable;
import android.content.integrity.Rule;

import com.android.internal.util.FrameworkStatsLog;

import java.util.Collections;
import java.util.List;

/**
 * A class encapsulating the result from the evaluation engine after evaluating rules against app
 * install metadata.
 *
 * <p>It contains the outcome effect (whether to allow or block the install), and the rule causing
 * that effect.
 */
public final class IntegrityCheckResult {

    public enum Effect {
        ALLOW,
        DENY
    }

    private final Effect mEffect;
    private final List<Rule> mRuleList;

    private IntegrityCheckResult(Effect effect, @Nullable List<Rule> ruleList) {
        this.mEffect = effect;
        this.mRuleList = ruleList;
    }

    public Effect getEffect() {
        return mEffect;
    }

    public List<Rule> getMatchedRules() {
        return mRuleList;
    }

    /**
     * Create an ALLOW evaluation outcome.
     *
     * @return An evaluation outcome with ALLOW effect and no rule.
     */
    public static IntegrityCheckResult allow() {
        return new IntegrityCheckResult(Effect.ALLOW, Collections.emptyList());
    }

    /**
     * Create an ALLOW evaluation outcome.
     *
     * @return An evaluation outcome with ALLOW effect and rule causing that effect.
     */
    public static IntegrityCheckResult allow(List<Rule> ruleList) {
        return new IntegrityCheckResult(Effect.ALLOW, ruleList);
    }

    /**
     * Create a DENY evaluation outcome.
     *
     * @param ruleList All valid rules that cause the DENY effect.
     * @return An evaluation outcome with DENY effect and rule causing that effect.
     */
    public static IntegrityCheckResult deny(List<Rule> ruleList) {
        return new IntegrityCheckResult(Effect.DENY, ruleList);
    }

    /**
     * Returns the in value of the integrity check result for logging purposes.
     */
    public int getLoggingResponse() {
        if (getEffect() == Effect.DENY) {
            return FrameworkStatsLog.INTEGRITY_CHECK_RESULT_REPORTED__RESPONSE__REJECTED;
        } else if (getEffect() == Effect.ALLOW && getMatchedRules().isEmpty()) {
            return FrameworkStatsLog.INTEGRITY_CHECK_RESULT_REPORTED__RESPONSE__ALLOWED;
        } else if (getEffect() == Effect.ALLOW && !getMatchedRules().isEmpty()) {
            return FrameworkStatsLog.INTEGRITY_CHECK_RESULT_REPORTED__RESPONSE__FORCE_ALLOWED;
        } else {
            throw new IllegalStateException("IntegrityCheckResult is not valid.");
        }
    }

    /** Returns true when the {@code mEffect} is caused by an app certificate mismatch. */
    public boolean isCausedByAppCertRule() {
        return mRuleList.stream().anyMatch(rule -> rule.getFormula().isAppCertificateFormula());
    }

    /** Returns true when the {@code mEffect} is caused by an installer rule. */
    public boolean isCausedByInstallerRule() {
        return mRuleList.stream().anyMatch(rule -> rule.getFormula().isInstallerFormula());
    }

}
