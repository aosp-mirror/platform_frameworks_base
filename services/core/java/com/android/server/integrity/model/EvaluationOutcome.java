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

/**
 * A class encapsulating the result from the evaluation engine after evaluating rules against app
 * install metadata.
 *
 * <p>It contains the outcome effect (whether to allow or block the install), and the rule causing
 * that effect.
 */
public final class EvaluationOutcome {

    public enum Effect {
        ALLOW,
        DENY
    }

    private final Effect mEffect;
    private final Rule mRule;

    private EvaluationOutcome(Effect effect, Rule rule) {
        this.mEffect = effect;
        this.mRule = rule;
    }

    public Effect getEffect() {
        return mEffect;
    }

    public Rule getRule() {
        return mRule;
    }

    /**
     * Create an ALLOW evaluation outcome.
     *
     * @return An evaluation outcome with ALLOW effect and empty rule.
     */
    public static EvaluationOutcome allow() {
        return new EvaluationOutcome(Effect.ALLOW, Rule.EMPTY);
    }

    /**
     * Create a DENY evaluation outcome.
     *
     * @param rule Rule causing the DENY effect.
     * @return An evaluation outcome with DENY effect and rule causing that effect.
     */
    public static EvaluationOutcome deny(Rule rule) {
        return new EvaluationOutcome(Effect.DENY, rule);
    }
}
