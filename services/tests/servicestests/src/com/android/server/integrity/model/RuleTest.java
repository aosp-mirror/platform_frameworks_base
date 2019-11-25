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
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

@RunWith(JUnit4.class)
public class RuleTest {

    private static final @Rule.Effect int DENY_EFFECT = Rule.DENY;
    private static final String PACKAGE_NAME = "com.test.app";
    private static final String APP_CERTIFICATE = "test_cert";
    private static final Formula PACKAGE_NAME_ATOMIC_FORMULA =
            new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, PACKAGE_NAME);
    private static final Formula APP_CERTIFICATE_ATOMIC_FORMULA =
            new AtomicFormula.StringAtomicFormula(AtomicFormula.APP_CERTIFICATE, APP_CERTIFICATE);

    @Test
    public void testValidRule() {
        Rule validRule = new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT);

        assertEquals(PACKAGE_NAME_ATOMIC_FORMULA, validRule.getFormula());
        assertEquals(DENY_EFFECT, validRule.getEffect());
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
        OpenFormula openFormula =
                new OpenFormula(
                        OpenFormula.AND,
                        Arrays.asList(PACKAGE_NAME_ATOMIC_FORMULA, APP_CERTIFICATE_ATOMIC_FORMULA));
        Rule rule = new Rule(openFormula, Rule.DENY);

        assertEquals(
                String.format(
                        "Rule: (PACKAGE_NAME EQ %s) AND (APP_CERTIFICATE EQ %s), DENY",
                        PACKAGE_NAME, APP_CERTIFICATE),
                rule.toString());
    }

    @Test
    public void testEquals_trueCase() {
        Rule rule1 = new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT);
        Rule rule2 = new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT);

        assertEquals(rule1, rule2);
    }

    @Test
    public void testEquals_falseCase() {
        Rule rule1 = new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT);
        Rule rule2 = new Rule(APP_CERTIFICATE_ATOMIC_FORMULA, DENY_EFFECT);

        assertNotEquals(rule1, rule2);
    }

    @Test
    public void testParcelUnparcel() {
        Rule rule =
                new Rule(
                        new OpenFormula(
                                OpenFormula.AND,
                                Arrays.asList(
                                        APP_CERTIFICATE_ATOMIC_FORMULA,
                                        new OpenFormula(
                                                OpenFormula.NOT,
                                                Arrays.asList(PACKAGE_NAME_ATOMIC_FORMULA)))),
                        Rule.DENY);
        Parcel p = Parcel.obtain();
        rule.writeToParcel(p, 0);
        p.setDataPosition(0);
        Rule newRule = Rule.CREATOR.createFromParcel(p);

        assertEquals(newRule, rule);
    }

    @Test
    public void testInvalidRule_invalidEffect() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */ "Unknown effect: -1",
                () -> new Rule(PACKAGE_NAME_ATOMIC_FORMULA, /* effect= */ -1));
    }
}
