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
package com.android.ravenwoodtest.runtimetest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemProperties;
import android.platform.test.ravenwood.RavenwoodRule;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

public class SystemPropertyTest {

    private static final String PROP_KEY_1 = "debug.ravenwood.prop1";
    private static final String PROP_VAL_1 = "ravenwood.1";
    private static final String PROP_KEY_2 = "debug.ravenwood.prop2";
    private static final String PROP_VAL_2 = "ravenwood.2";
    private static final String PROP_KEY_3 = "debug.ravenwood.prop3";
    private static final String PROP_VAL_3 = "ravenwood.3";
    private static final String PROP_VAL_4 = "ravenwood.4";

    @ClassRule(order = 0)
    public static TestRule mCheckClassRule = (base, description) -> new Statement() {
        @Override
        public void evaluate() throws Throwable {
            assertTrue(SystemProperties.get(PROP_KEY_1).isEmpty());
            assertTrue(SystemProperties.get(PROP_KEY_3).isEmpty());
            try {
                base.evaluate();
            } finally {
                assertTrue(SystemProperties.get(PROP_KEY_1).isEmpty());
                assertTrue(SystemProperties.get(PROP_KEY_3).isEmpty());
            }
        }
    };

    @ClassRule(order = 1)
    public static RavenwoodRule mClassRule = new RavenwoodRule.Builder()
            .setSystemPropertyImmutable(PROP_KEY_1, PROP_VAL_1)
            .setSystemPropertyImmutable(PROP_KEY_3, PROP_VAL_4)
            .build();

    @Rule(order = 0)
    public TestRule mCheckRule = (base, description) -> new Statement() {
        @Override
        public void evaluate() throws Throwable {
            assertTrue(SystemProperties.get(PROP_KEY_2).isEmpty());
            assertEquals(SystemProperties.get(PROP_KEY_3), PROP_VAL_4);
            try {
                base.evaluate();
            } finally {
                assertTrue(SystemProperties.get(PROP_KEY_2).isEmpty());
                assertEquals(SystemProperties.get(PROP_KEY_3), PROP_VAL_4);
            }
        }
    };

    @Rule(order = 1)
    public RavenwoodRule mRule = new RavenwoodRule.Builder()
            .setSystemPropertyImmutable(PROP_KEY_2, PROP_VAL_2)
            .setSystemPropertyImmutable(PROP_KEY_3, PROP_VAL_3)
            .build();

    @Test
    public void testRavenwoodRuleSetProperty() {
        assertEquals(SystemProperties.get(PROP_KEY_1), PROP_VAL_1);
        assertEquals(SystemProperties.get(PROP_KEY_2), PROP_VAL_2);
        assertEquals(SystemProperties.get(PROP_KEY_3), PROP_VAL_3);
    }
}
