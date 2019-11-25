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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

@RunWith(JUnit4.class)
public class OpenFormulaTest {

    private static final AtomicFormula ATOMIC_FORMULA_1 =
            new AtomicFormula.StringAtomicFormula(AtomicFormula.PACKAGE_NAME, "test1");
    private static final AtomicFormula ATOMIC_FORMULA_2 =
            new AtomicFormula.IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1);

    @Test
    public void testValidOpenFormula() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));

        assertEquals(OpenFormula.AND, openFormula.getConnector());
        assertEquals(Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2), openFormula.getFormulas());
    }

    @Test
    public void testValidateAuxiliaryFormula_binaryConnectors() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                "Connector AND must have at least 2 formulas",
                () ->
                        new OpenFormula(
                                OpenFormula.AND, Collections.singletonList(ATOMIC_FORMULA_1)));
    }

    @Test
    public void testValidateAuxiliaryFormula_unaryConnectors() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                "Connector NOT must have 1 formula only",
                () ->
                        new OpenFormula(
                                OpenFormula.NOT,
                                Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2)));
    }

    @Test
    public void testIsSatisfiable_notFalse_true() {
        OpenFormula openFormula = new OpenFormula(OpenFormula.NOT,
                Collections.singletonList(ATOMIC_FORMULA_1));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));

        assertTrue(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_notTrue_false() {
        OpenFormula openFormula = new OpenFormula(OpenFormula.NOT,
                Collections.singletonList(ATOMIC_FORMULA_1));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));

        assertFalse(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueAndTrue_true() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueAndFalse_false() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseAndTrue_false() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseAndFalse_false() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueOrTrue_true() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_trueOrFalse_true() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertTrue(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseOrTrue_true() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertTrue(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertTrue(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_falseOrFalse_false() {
        OpenFormula openFormula =
                new OpenFormula(OpenFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(2).build();
        // validate assumptions about the metadata
        assertFalse(ATOMIC_FORMULA_1.isSatisfied(appInstallMetadata));
        assertFalse(ATOMIC_FORMULA_2.isSatisfied(appInstallMetadata));

        assertFalse(openFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testParcelUnparcel() {
        OpenFormula formula =
                new OpenFormula(OpenFormula.AND, Arrays.asList(ATOMIC_FORMULA_2, ATOMIC_FORMULA_1));
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        OpenFormula newFormula = OpenFormula.CREATOR.createFromParcel(p);

        assertEquals(formula, newFormula);
    }

    @Test
    public void testInvalidOpenFormula_invalidConnector() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */ "Unknown connector: -1",
                () -> new OpenFormula(/* connector= */ -1,
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
