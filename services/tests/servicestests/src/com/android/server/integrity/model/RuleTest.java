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

import java.util.Arrays;

@RunWith(JUnit4.class)
public class RuleTest {

    private static final Rule.Effect DENY_EFFECT = Rule.Effect.DENY;
    private static final String PACKAGE_NAME = "com.test.app";
    private static final String APP_CERTIFICATE = "test_cert";
    private static final Formula PACKAGE_NAME_ATOMIC_FORMULA =
            new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                    PACKAGE_NAME);
    private static final Formula APP_CERTIFICATE_ATOMIC_FORMULA =
            new AtomicFormula(AtomicFormula.Key.APP_CERTIFICATE, AtomicFormula.Operator.EQ,
                    APP_CERTIFICATE);

    @Test
    public void testEmptyRule() {
        Rule emptyRule = Rule.EMPTY;

        assertNull(emptyRule.getFormula());
        assertNull(emptyRule.getEffect());
    }

    @Test
    public void testValidRule() {
        Rule validRule = new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT);

        assertEquals(PACKAGE_NAME_ATOMIC_FORMULA, validRule.getFormula());
        assertEquals(DENY_EFFECT, validRule.getEffect());
    }

    @Test
    public void testInvalidRule_invalidEffect() {
        assertExpectException(
                NullPointerException.class,
                /* expectedExceptionMessageRegex */ null,
                () -> new Rule(PACKAGE_NAME_ATOMIC_FORMULA, null));
    }

    @Test
    public void testInvalidRule_invalidFormula() {
        assertExpectException(
                NullPointerException.class,
                /* expectedExceptionMessageRegex */ null,
                () -> new Rule(null, DENY_EFFECT));
    }

    @Test
    public void testToString() {
        OpenFormula openFormula = new OpenFormula(OpenFormula.Connector.AND,
                Arrays.asList(PACKAGE_NAME_ATOMIC_FORMULA, APP_CERTIFICATE_ATOMIC_FORMULA));
        Rule rule = new Rule(openFormula, Rule.Effect.DENY);

        String toString = rule.toString();

        assertEquals(String.format("Rule: PACKAGE_NAME EQ %s AND APP_CERTIFICATE EQ %s, DENY",
                PACKAGE_NAME, APP_CERTIFICATE), toString);
    }
}
