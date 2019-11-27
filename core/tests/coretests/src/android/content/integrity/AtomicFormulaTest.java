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

import android.content.integrity.AtomicFormula.BooleanAtomicFormula;
import android.content.integrity.AtomicFormula.IntAtomicFormula;
import android.content.integrity.AtomicFormula.StringAtomicFormula;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AtomicFormulaTest {

    @Test
    public void testValidAtomicFormula_stringValue() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */ false);

        assertEquals(AtomicFormula.PACKAGE_NAME, stringAtomicFormula.getKey());
    }

    @Test
    public void testValidAtomicFormula_intValue() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.LE, 1);

        assertEquals(AtomicFormula.VERSION_CODE, intAtomicFormula.getKey());
    }

    @Test
    public void testValidAtomicFormula_boolValue() {
        BooleanAtomicFormula atomicFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);

        assertEquals(AtomicFormula.PRE_INSTALLED, atomicFormula.getKey());
    }

    @Test
    public void testInvalidAtomicFormula_stringValue() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Key VERSION_CODE cannot be used with StringAtomicFormula"),
                () ->
                        new StringAtomicFormula(
                                AtomicFormula.VERSION_CODE,
                                "test-value",
                                /* isHashedValue= */ false));
    }

    @Test
    public void testInvalidAtomicFormula_intValue() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Key PACKAGE_NAME cannot be used with IntAtomicFormula"),
                () -> new IntAtomicFormula(AtomicFormula.PACKAGE_NAME, AtomicFormula.EQ, 1));
    }

    @Test
    public void testInvalidAtomicFormula_boolValue() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Key PACKAGE_NAME cannot be used with BooleanAtomicFormula"),
                () -> new BooleanAtomicFormula(AtomicFormula.PACKAGE_NAME, true));
    }

    @Test
    public void testIsSatisfiable_string_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */ false);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("com.test.app").build();

        assertTrue(stringAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_string_false() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */ false);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("com.foo.bar").build();

        assertFalse(stringAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_eq_true() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertTrue(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_eq_false() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(1).build();

        assertFalse(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_gt_true() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GT, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(1).build();

        assertTrue(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_gt_false() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GT, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertFalse(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_ge_true() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GE, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(1).build();

        assertTrue(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_ge_false() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GE, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertFalse(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_lt_true() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.LT, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertTrue(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_lt_false() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.LT, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(2).build();

        assertFalse(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_le_true() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.LE, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertTrue(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_int_le_false() {
        IntAtomicFormula intAtomicFormula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.LE, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(2).build();

        assertFalse(intAtomicFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_bool_true() {
        BooleanAtomicFormula boolFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setIsPreInstalled(true).build();

        assertTrue(boolFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testIsSatisfiable_bool_false() {
        BooleanAtomicFormula boolFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setIsPreInstalled(false).build();

        assertFalse(boolFormula.isSatisfied(appInstallMetadata));
    }

    @Test
    public void testParcelUnparcel_string() {
        StringAtomicFormula formula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "abc", /* isHashedValue= */ false);
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        StringAtomicFormula newFormula = StringAtomicFormula.CREATOR.createFromParcel(p);

        assertEquals(formula, newFormula);
    }

    @Test
    public void testParcelUnparcel_int() {
        IntAtomicFormula formula =
                new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.LT, 1);
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        IntAtomicFormula newFormula = IntAtomicFormula.CREATOR.createFromParcel(p);

        assertEquals(formula, newFormula);
    }

    @Test
    public void testParcelUnparcel_bool() {
        BooleanAtomicFormula formula = new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        BooleanAtomicFormula newFormula = BooleanAtomicFormula.CREATOR.createFromParcel(p);

        assertEquals(formula, newFormula);
    }

    @Test
    public void testInvalidAtomicFormula_invalidKey() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */ "Unknown key: -1",
                () -> new IntAtomicFormula(/* key= */ -1, AtomicFormula.EQ, 0));
    }

    @Test
    public void testInvalidAtomicFormula_invalidOperator() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */ "Unknown operator: -1",
                () -> new IntAtomicFormula(AtomicFormula.VERSION_CODE, /* operator= */ -1, 0));
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
