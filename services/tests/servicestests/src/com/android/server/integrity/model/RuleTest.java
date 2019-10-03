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

import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RuleTest {

    private static final Rule.Effect DENY_EFFECT = Rule.Effect.DENY;
    private static final Rule.Formula SIMPLE_FORMULA =
            new Rule.AtomicFormula(Rule.Key.PACKAGE_NAME, Rule.Operator.EQ, "com.test.app");

    @Test
    public void testEmptyRule() {
        Rule emptyRule = Rule.EMPTY;

        assertNull(emptyRule.getFormula());
        assertNull(emptyRule.getEffect());
    }

    @Test
    public void testValidRule() {
        Rule validRule = new Rule(SIMPLE_FORMULA, DENY_EFFECT);

        assertEquals(SIMPLE_FORMULA, validRule.getFormula());
        assertEquals(DENY_EFFECT, validRule.getEffect());
    }

    @Test
    public void testInvalidRule_invalidEffect() {
        assertExpectException(
                NullPointerException.class,
                /* expectedExceptionMessageRegex */ null,
                () -> new Rule(SIMPLE_FORMULA, null));
    }

    @Test
    public void testInvalidRule_invalidFormula() {
        assertExpectException(
                NullPointerException.class,
                /* expectedExceptionMessageRegex */ null,
                () -> new Rule(null, DENY_EFFECT));
    }
}
