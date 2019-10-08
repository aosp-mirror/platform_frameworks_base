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

import static com.android.internal.util.Preconditions.checkNotNull;

/**
 * Represent rules to be used in the rule evaluation engine to match against app installs.
 *
 * <p>Instances of this class are immutable.
 */
public final class Rule {

    enum Effect {
        DENY
    }

    // Holds an empty rule instance.
    public static final Rule EMPTY = new Rule();

    private final Formula mFormula;
    private final Effect mEffect;

    private Rule() {
        this.mFormula = null;
        this.mEffect = null;
    }

    public Rule(Formula formula, Effect effect) {
        this.mFormula = checkNotNull(formula);
        this.mEffect = checkNotNull(effect);
    }

    /**
     * Indicates whether the rule is empty or not.
     *
     * @return {@code true} if the rule is empty, and {@code false} otherwise.
     */
    public boolean isEmpty() {
        return mFormula == null && mEffect == null;
    }

    public Formula getFormula() {
        return mFormula;
    }

    public Effect getEffect() {
        return mEffect;
    }
}
