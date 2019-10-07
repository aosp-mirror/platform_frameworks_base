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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AtomicFormulaTest {

    @Test
    public void testValidAtomicFormula_stringValue() {
        AtomicFormula atomicFormula = new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME,
                AtomicFormula.Operator.EQ, "com.test.app");

        assertEquals(AtomicFormula.Key.PACKAGE_NAME, atomicFormula.getKey());
        assertEquals(AtomicFormula.Operator.EQ, atomicFormula.getOperator());
        assertEquals("com.test.app", atomicFormula.getStringValue());
    }

    @Test
    public void testValidAtomicFormula_intValue() {
        AtomicFormula atomicFormula = new AtomicFormula(AtomicFormula.Key.VERSION_CODE,
                AtomicFormula.Operator.LE, 1);

        assertEquals(AtomicFormula.Key.VERSION_CODE, atomicFormula.getKey());
        assertEquals(AtomicFormula.Operator.LE, atomicFormula.getOperator());
        assertEquals(1, atomicFormula.getIntValue().intValue());
    }

    @Test
    public void testValidAtomicFormula_boolValue() {
        AtomicFormula atomicFormula = new AtomicFormula(AtomicFormula.Key.PRE_INSTALLED,
                AtomicFormula.Operator.EQ, true);

        assertEquals(AtomicFormula.Key.PRE_INSTALLED, atomicFormula.getKey());
        assertEquals(AtomicFormula.Operator.EQ, atomicFormula.getOperator());
        assertEquals(true, atomicFormula.getBoolValue());
    }

    @Test
    public void testInvalidAtomicFormula_stringValue() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Key %s cannot have string value", AtomicFormula.Key.VERSION_CODE),
                () -> new AtomicFormula(AtomicFormula.Key.VERSION_CODE, AtomicFormula.Operator.EQ,
                        "test-value"));
    }

    @Test
    public void testInvalidAtomicFormula_intValue() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Key %s cannot have integer value", AtomicFormula.Key.PACKAGE_NAME),
                () -> new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        1));
    }

    @Test
    public void testInvalidAtomicFormula_boolValue() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Key %s cannot have boolean value", AtomicFormula.Key.PACKAGE_NAME),
                () -> new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ,
                        true));
    }

    @Test
    public void testValidateOperator_invalidKeyOperatorPair() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Invalid operator %s used for key %s",
                        AtomicFormula.Operator.LE, AtomicFormula.Key.PACKAGE_NAME),
                () -> new AtomicFormula(AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.LE,
                        "test-value"));
    }
}
