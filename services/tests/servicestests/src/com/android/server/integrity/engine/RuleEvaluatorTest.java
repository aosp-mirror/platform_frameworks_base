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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.android.server.integrity.model.AppInstallMetadata;
import com.android.server.integrity.model.AtomicFormula;
import com.android.server.integrity.model.OpenFormula;
import com.android.server.integrity.model.Rule;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class RuleEvaluatorTest {

    private static final String PACKAGE_NAME_1 = "com.test.app";
    private static final String PACKAGE_NAME_2 = "com.test.app2";
    private static final String APP_CERTIFICATE = "test_cert";
    private static final AppInstallMetadata APP_INSTALL_METADATA =
            new AppInstallMetadata.Builder()
                    .setPackageName(PACKAGE_NAME_1)
                    .setAppCertificate(APP_CERTIFICATE)
                    .build();

    @Test
    public void testMatchRules_emptyRules() {
        List<Rule> rules = new ArrayList<>();

        Rule matchedRule = RuleEvaluator.evaluateRules(rules, APP_INSTALL_METADATA);

        assertEquals(Rule.EMPTY, matchedRule);
    }

    @Test
    public void testMatchRules_emptyMatch() {
        Rule rule1 = new Rule(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_2), Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Collections.singletonList(rule1),
                APP_INSTALL_METADATA);

        assertEquals(Rule.EMPTY, matchedRule);
    }


    @Test
    public void testMatchRules_oneMatch() {
        Rule rule1 = new Rule(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_1), Rule.Effect.DENY);
        Rule rule2 = new Rule(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_2), Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Arrays.asList(rule1, rule2),
                APP_INSTALL_METADATA);

        assertEquals(rule1, matchedRule);
    }

    @Test
    public void testMatchRules_multipleMatches() {
        Rule rule1 = new Rule(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_1), Rule.Effect.DENY);
        OpenFormula openFormula2 = new OpenFormula(OpenFormula.Connector.AND, Arrays.asList(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_1),
                new AtomicFormula(AtomicFormula.Key.APP_CERTIFICATE,
                        AtomicFormula.Operator.EQ,
                        APP_CERTIFICATE)));
        Rule rule2 = new Rule(
                openFormula2, Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Arrays.asList(rule1, rule2),
                APP_INSTALL_METADATA);

        assertNotEquals(Rule.EMPTY, matchedRule);
    }

    @Test
    public void testMatchRules_ruleWithNot() {
        OpenFormula openFormula = new OpenFormula(OpenFormula.Connector.NOT,
                Collections.singletonList(
                        new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                                PACKAGE_NAME_2)));
        Rule rule = new Rule(openFormula, Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Collections.singletonList(rule),
                APP_INSTALL_METADATA);

        assertEquals(rule, matchedRule);
    }

    @Test
    public void testMatchRules_ruleWithIntegerOperators() {
        Rule rule1 = new Rule(
                new AtomicFormula(AtomicFormula.Key.VERSION_CODE, AtomicFormula.Operator.GT,
                        1), Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Collections.singletonList(rule1),
                APP_INSTALL_METADATA);

        assertEquals(rule1, matchedRule);
    }

    @Test
    public void testMatchRules_validForm() {
        OpenFormula openFormula = new OpenFormula(OpenFormula.Connector.AND, Arrays.asList(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_1),
                new AtomicFormula(AtomicFormula.Key.APP_CERTIFICATE,
                        AtomicFormula.Operator.EQ,
                        APP_CERTIFICATE)));
        Rule rule = new Rule(
                openFormula, Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Collections.singletonList(rule),
                APP_INSTALL_METADATA);

        assertEquals(rule, matchedRule);
    }

    @Test
    public void testMatchRules_ruleNotInDNF() {
        OpenFormula openFormula = new OpenFormula(OpenFormula.Connector.OR, Arrays.asList(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_1),
                new AtomicFormula(AtomicFormula.Key.APP_CERTIFICATE,
                        AtomicFormula.Operator.EQ,
                        APP_CERTIFICATE)));
        Rule rule = new Rule(
                openFormula, Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Collections.singletonList(rule),
                APP_INSTALL_METADATA);

        assertEquals(Rule.EMPTY, matchedRule);
    }

    @Test
    public void testMatchRules_openFormulaWithNot() {
        OpenFormula openSubFormula = new OpenFormula(OpenFormula.Connector.AND, Arrays.asList(
                new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        PACKAGE_NAME_2),
                new AtomicFormula(AtomicFormula.Key.APP_CERTIFICATE,
                        AtomicFormula.Operator.EQ,
                        APP_CERTIFICATE)));
        OpenFormula openFormula = new OpenFormula(OpenFormula.Connector.NOT,
                Collections.singletonList(openSubFormula));
        Rule rule = new Rule(
                openFormula, Rule.Effect.DENY);

        Rule matchedRule = RuleEvaluator.evaluateRules(Collections.singletonList(rule),
                APP_INSTALL_METADATA);

        assertEquals(Rule.EMPTY, matchedRule);
    }
}
