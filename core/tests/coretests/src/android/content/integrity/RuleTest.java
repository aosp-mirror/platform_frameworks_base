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

package android.content.integrity;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

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
    private static final IntegrityFormula PACKAGE_NAME_ATOMIC_FORMULA =
            new AtomicFormula.StringAtomicFormula(
                    AtomicFormula.PACKAGE_NAME,
                    PACKAGE_NAME,
                    /* isHashedValue= */ false);
    private static final IntegrityFormula APP_CERTIFICATE_ATOMIC_FORMULA =
            new AtomicFormula.StringAtomicFormula(
                    AtomicFormula.APP_CERTIFICATE,
                    APP_CERTIFICATE,
                    /* isHashedValue= */ false);

    @Test
    public void testValidRule() {
        Rule validRule = new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT);

        assertThat(validRule.getFormula()).isEqualTo(PACKAGE_NAME_ATOMIC_FORMULA);
        assertThat(validRule.getEffect()).isEqualTo(DENY_EFFECT);
    }

    @Test
    public void testInvalidRule_invalidFormula() {
        Exception e =
                expectThrows(
                        NullPointerException.class,
                        () -> new Rule(null, DENY_EFFECT));
    }

    @Test
    public void testToString() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(PACKAGE_NAME_ATOMIC_FORMULA, APP_CERTIFICATE_ATOMIC_FORMULA));
        Rule rule = new Rule(compoundFormula, Rule.DENY);

        assertThat(rule.toString())
                .isEqualTo(
                        String.format(
                                "Rule: (PACKAGE_NAME EQ %s) AND (APP_CERTIFICATE EQ %s), DENY",
                                PACKAGE_NAME, APP_CERTIFICATE));
    }

    @Test
    public void testEquals_trueCase() {
        assertThat(new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT))
                .isEqualTo(new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT));
    }

    @Test
    public void testEquals_falseCase() {
        assertThat(new Rule(PACKAGE_NAME_ATOMIC_FORMULA, DENY_EFFECT))
                .isNotEqualTo(new Rule(APP_CERTIFICATE_ATOMIC_FORMULA, DENY_EFFECT));
    }

    @Test
    public void testParcelUnparcel() {
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        APP_CERTIFICATE_ATOMIC_FORMULA,
                                        new CompoundFormula(
                                                CompoundFormula.NOT,
                                                Arrays.asList(PACKAGE_NAME_ATOMIC_FORMULA)))),
                        Rule.DENY);
        Parcel p = Parcel.obtain();
        rule.writeToParcel(p, 0);
        p.setDataPosition(0);

        assertThat(Rule.CREATOR.createFromParcel(p)).isEqualTo(rule);
    }

    @Test
    public void testInvalidRule_invalidEffect() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () -> new Rule(PACKAGE_NAME_ATOMIC_FORMULA, /* effect= */ -1));
        assertThat(e.getMessage()).isEqualTo("Unknown effect: -1");
    }
}
