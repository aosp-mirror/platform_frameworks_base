/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwoodtest.bivalenttest.ravenizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.HashMap;

/**
 * Make sure ravenizer will inject implicit rules and rewrite the existing rules' orders.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodImplicitRuleOrderRewriteTest {

    private static final TestRule sEmptyRule = (statement, description) -> statement;

    // We have two sets of 9 rules below, for class rules and instance rules.
    // - Ravenizer will inject 2 more rules of each kind.
    // - Ravenizer will adjust their order, so even though we'll add two sets of class and instance
    //  rules with a MIN / MAX order, there will still be no duplicate in the order.

    private static final int EXPECTED_RULE_COUNT = 9 + 2;

    @ClassRule(order = Integer.MIN_VALUE)
    public static final TestRule sRule01 = sEmptyRule;

    @ClassRule(order = Integer.MIN_VALUE + 1)
    public static final TestRule sRule02 = sEmptyRule;

    @ClassRule(order = -10)
    public static final TestRule sRule03 = sEmptyRule;

    @ClassRule(order = -1)
    public static final TestRule sRule04 = sEmptyRule;

    @ClassRule(order = 0)
    public static final TestRule sRule05 = sEmptyRule;

    @ClassRule(order = 1)
    public static final TestRule sRule06 = sEmptyRule;

    @ClassRule(order = 10)
    public static final TestRule sRule07 = sEmptyRule;

    @ClassRule(order = Integer.MAX_VALUE - 1)
    public static final TestRule sRule08 = sEmptyRule;

    @ClassRule(order = Integer.MAX_VALUE)
    public static final TestRule sRule09 = sEmptyRule;

    @Rule(order = Integer.MIN_VALUE)
    public final TestRule mRule01 = sEmptyRule;

    @Rule(order = Integer.MIN_VALUE + 1)
    public final TestRule mRule02 = sEmptyRule;

    @Rule(order = -10)
    public final TestRule mRule03 = sEmptyRule;

    @Rule(order = -1)
    public final TestRule mRule04 = sEmptyRule;

    @Rule(order = 0)
    public final TestRule mRule05 = sEmptyRule;

    @Rule(order = 1)
    public final TestRule mRule06 = sEmptyRule;

    @Rule(order = 10)
    public final TestRule mRule07 = sEmptyRule;

    @Rule(order = Integer.MAX_VALUE - 1)
    public final TestRule mRule08 = sEmptyRule;

    @Rule(order = Integer.MAX_VALUE)
    public final TestRule mRule09 = sEmptyRule;

    private void checkRules(boolean classRule) {
        final var anotClass = classRule ? ClassRule.class : Rule.class;

        final HashMap<Integer, Integer> ordersUsed = new HashMap<>();

        for (var field : this.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(anotClass)) {
                continue;
            }
            final var anot = field.getAnnotation(anotClass);
            final int order = classRule ? ((ClassRule) anot).order() : ((Rule) anot).order();

            if (ordersUsed.containsKey(order)) {
                fail("Detected duplicate order=" + order);
            }
            ordersUsed.put(order, 1);
        }
        assertEquals(EXPECTED_RULE_COUNT, ordersUsed.size());
    }

    @Test
    public void testClassRules() {
        Assume.assumeTrue(RavenwoodRule.isOnRavenwood());

        checkRules(true);
    }

    @Test
    public void testInstanceRules() {
        Assume.assumeTrue(RavenwoodRule.isOnRavenwood());

        checkRules(false);
    }
}
