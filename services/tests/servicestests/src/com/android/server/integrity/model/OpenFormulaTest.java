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
public class OpenFormulaTest {

    private static final AtomicFormula ATOMIC_FORMULA_1 = new AtomicFormula(
            AtomicFormula.Key.PACKAGE_NAME, AtomicFormula.Operator.EQ, "test1");
    private static final AtomicFormula ATOMIC_FORMULA_2 = new AtomicFormula(
            AtomicFormula.Key.VERSION_CODE, AtomicFormula.Operator.EQ, 1);

    @Test
    public void testValidOpenFormula() {
        OpenFormula openFormula = new OpenFormula(OpenFormula.Connector.AND, ATOMIC_FORMULA_1,
                ATOMIC_FORMULA_2);

        assertEquals(OpenFormula.Connector.AND, openFormula.getConnector());
        assertEquals(ATOMIC_FORMULA_1, openFormula.getMainFormula());
        assertEquals(ATOMIC_FORMULA_2, openFormula.getAuxiliaryFormula());
    }

    @Test
    public void testValidateAuxiliaryFormula_binaryConnectors() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Invalid formulas used for connector %s", OpenFormula.Connector.AND),
                () -> new OpenFormula(OpenFormula.Connector.AND, ATOMIC_FORMULA_1,
                        null));
    }

    @Test
    public void testValidateAuxiliaryFormula_unaryConnectors() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Invalid formulas used for connector %s", OpenFormula.Connector.NOT),
                () -> new OpenFormula(OpenFormula.Connector.NOT, ATOMIC_FORMULA_1,
                        ATOMIC_FORMULA_2));
    }
}
