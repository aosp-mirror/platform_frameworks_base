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

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.AtomicFormula.BooleanAtomicFormula;
import android.content.integrity.AtomicFormula.StringAtomicFormula;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AtomicFormulaTest {

    @Test
    public void testValidAtomicFormula_stringValue() {
        String packageName = "com.test.app";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, packageName, /* isHashedValue= */false);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.PACKAGE_NAME);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(packageName);
        assertThat(stringAtomicFormula.getIsHashedValue()).isFalse();
    }

    @Test
    public void testValidAtomicFormula_stringValue_autoHash_packageNameLessThanLimit() {
        String packageName = "com.test.app";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, packageName);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.PACKAGE_NAME);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(packageName);
        assertThat(stringAtomicFormula.getIsHashedValue()).isFalse();
    }

    @Test
    public void testValidAtomicFormula_stringValue_autoHash_longPackageName() {
        String packageName = "com.test.app.test.app.test.app.test.app.test.app";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.PACKAGE_NAME, packageName);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.PACKAGE_NAME);
        assertThat(stringAtomicFormula.getValue()).doesNotMatch(packageName);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void testValidAtomicFormula_stringValue_autoHash_installerNameLessThanLimit() {
        String installerName = "com.test.app";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.INSTALLER_NAME, installerName);


        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.INSTALLER_NAME);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(installerName);
        assertThat(stringAtomicFormula.getIsHashedValue()).isFalse();
    }

    @Test
    public void testValidAtomicFormula_stringValue_autoHash_longInstallerName() {
        String installerName = "com.test.app.test.app.test.app.test.app.test.app";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.INSTALLER_NAME, installerName);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.INSTALLER_NAME);
        assertThat(stringAtomicFormula.getValue()).doesNotMatch(installerName);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void testValidAtomicFormula_stringValue_appCertificateAutoHashed() {
        String appCert = "cert";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.APP_CERTIFICATE, appCert);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.APP_CERTIFICATE);
        assertThat(stringAtomicFormula.getValue()).doesNotMatch(appCert);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void testValidAtomicFormula_stringValue_installerCertificateAutoHashed() {
        String installerCert = "cert";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.INSTALLER_CERTIFICATE,
                        installerCert);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(
                AtomicFormula.INSTALLER_CERTIFICATE);
        assertThat(stringAtomicFormula.getValue()).doesNotMatch(installerCert);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void testValidAtomicFormula_longValue() {
        AtomicFormula.LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GTE, 1);

        assertThat(longAtomicFormula.getKey()).isEqualTo(AtomicFormula.VERSION_CODE);
        assertThat(longAtomicFormula.getValue()).isEqualTo(1);
    }

    @Test
    public void testValidAtomicFormula_boolValue() {
        BooleanAtomicFormula atomicFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);

        assertThat(atomicFormula.getKey()).isEqualTo(AtomicFormula.PRE_INSTALLED);
        assertThat(atomicFormula.getValue()).isTrue();
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
    public void testInvalidAtomicFormula_longValue() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */
                String.format("Key PACKAGE_NAME cannot be used with LongAtomicFormula"),
                () ->
                        new AtomicFormula.LongAtomicFormula(
                                AtomicFormula.PACKAGE_NAME, AtomicFormula.EQ, 1));
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
    public void testParcelUnparcel_string() {
        StringAtomicFormula formula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "abc", /* isHashedValue= */ false);
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        StringAtomicFormula newFormula = StringAtomicFormula.CREATOR.createFromParcel(p);

        assertThat(newFormula).isEqualTo(formula);
    }

    @Test
    public void testParcelUnparcel_int() {
        AtomicFormula.LongAtomicFormula formula =
                new AtomicFormula.LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GT, 1);
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        AtomicFormula.LongAtomicFormula newFormula =
                AtomicFormula.LongAtomicFormula.CREATOR.createFromParcel(p);

        assertThat(newFormula).isEqualTo(formula);
    }

    @Test
    public void testParcelUnparcel_bool() {
        BooleanAtomicFormula formula = new BooleanAtomicFormula(
                AtomicFormula.PRE_INSTALLED, true);
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        BooleanAtomicFormula newFormula = BooleanAtomicFormula.CREATOR.createFromParcel(p);

        assertThat(newFormula).isEqualTo(formula);
    }

    @Test
    public void testInvalidAtomicFormula_invalidKey() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */ "Unknown key: -1",
                () -> new AtomicFormula.LongAtomicFormula(/* key= */ -1, AtomicFormula.EQ, 0));
    }

    @Test
    public void testInvalidAtomicFormula_invalidOperator() {
        assertExpectException(
                IllegalArgumentException.class,
                /* expectedExceptionMessageRegex */ "Unknown operator: -1",
                () ->
                        new AtomicFormula.LongAtomicFormula(
                                AtomicFormula.VERSION_CODE, /* operator= */ -1, 0));
    }

    @Test
    public void testFormulaMatches_string_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */
                        false);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("com.test.app").build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_string_false() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */
                        false);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("com.foo.bar").build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_long_eq_true() {
        AtomicFormula.LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_long_eq_false() {
        AtomicFormula.LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(1).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_long_gt_true() {
        AtomicFormula.LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GT,
                        0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(1).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_long_gt_false() {
        AtomicFormula.LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GT,
                        1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_long_gte_true() {
        AtomicFormula.LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GTE, 1);

        AppInstallMetadata appInstallMetadata1 =
                getAppInstallMetadataBuilder().setVersionCode(1).build();
        assertThat(longAtomicFormula.matches(appInstallMetadata1)).isTrue();

        AppInstallMetadata appInstallMetadata2 =
                getAppInstallMetadataBuilder().setVersionCode(2).build();
        assertThat(longAtomicFormula.matches(appInstallMetadata2)).isTrue();
    }

    @Test
    public void testFormulaMatches_long_gte_false() {
        AtomicFormula.LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GTE, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_bool_true() {
        BooleanAtomicFormula boolFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setIsPreInstalled(true).build();

        assertThat(boolFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_bool_false() {
        BooleanAtomicFormula boolFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setIsPreInstalled(false).build();

        assertThat(boolFormula.matches(appInstallMetadata)).isFalse();
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
