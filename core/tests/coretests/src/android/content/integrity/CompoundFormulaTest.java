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

import static android.content.integrity.TestUtils.assertExpectException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

@RunWith(JUnit4.class)
public class CompoundFormulaTest {

    private static final AtomicFormula ATOMIC_FORMULA_1 =
            new AtomicFormula.StringAtomicFormula(
                    AtomicFormula.PACKAGE_NAME, "test1", /* isHashedValue= */ false);
    private static final AtomicFormula ATOMIC_FORMULA_2 =
            new AtomicFormula.IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1);

    @Test
    public void testValidCompoundFormula() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));

        assertEquals(CompoundFormula.AND, compoundFormula.getConnector());
        assertEquals(
                Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2), compoundFormula.getFormulas());
    }

    @Test
    public void testValidateAuxiliaryFormula_binaryConnectors() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                "Connector AND must have at least 2 formulas",
                () ->
                        new CompoundFormula(
                                CompoundFormula.AND, Collections.singletonList(ATOMIC_FORMULA_1)));
    }

    @Test
    public void testValidateAuxiliaryFormula_unaryConnectors() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                "Connector NOT must have 1 formula only",
                () ->
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2)));
    }

    @Test
    public void testIsSatisfiable_notFalse_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(CompoundFormula.NOT, Arrays.asList(ATOMIC_FORMULA_1));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));

        assertTrue(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_notTrue_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(CompoundFormula.NOT, Arrays.asList(ATOMIC_FORMULA_1));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));

        assertFalse(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueAndTrue_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueAndFalse_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseAndTrue_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseAndFalse_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueOrTrue_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueOrFalse_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseOrTrue_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseOrFalse_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(compoundFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testParcelUnparcel() {
        CompoundFormula formula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_2, ATOMIC_FORMULA_1));
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        CompoundFormula newFormula = CompoundFormula.CREATOR.createFromParcel(p);

        assertEquals(formula, newFormula);
    }

    @Test
    public void testInvalidCompoundFormula_invalidConnector() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */ "Unknown connector: -1",
                () ->
                        new CompoundFormula(
                                /* connector= */ -1,
                                Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2)));
    }

    /** Returns a builder with all fields filled with some dummy data. */
    private AppInstallMetadata.Builder getAppInstallMetadataBuilder() {
        return new AppInstallMetadata.Builder()
                .setPackageName("abc")
                .setAppCertificate("abc")
                .setInstallerCertificate("abc")
                .setInstallerName("abc")
                .setVersionCode(-1)
                .setIsPreInstalled(true);
    }
}
