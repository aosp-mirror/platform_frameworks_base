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

import android.content.integrity.AtomicFormula.BooleanAtomicFormula;
import android.content.integrity.AtomicFormula.LongAtomicFormula;
import android.content.integrity.AtomicFormula.StringAtomicFormula;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

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
    public void testValidAtomicFormula_stringValue_appCertificateIsNotAutoHashed() {
        String appCert = "cert";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.APP_CERTIFICATE, appCert);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.APP_CERTIFICATE);
        assertThat(stringAtomicFormula.getValue()).matches(appCert);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void testValidAtomicFormula_stringValue_installerCertificateIsNotAutoHashed() {
        String installerCert = "cert";
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(AtomicFormula.INSTALLER_CERTIFICATE,
                        installerCert);

        assertThat(stringAtomicFormula.getKey()).isEqualTo(
                AtomicFormula.INSTALLER_CERTIFICATE);
        assertThat(stringAtomicFormula.getValue()).matches(installerCert);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void testValidAtomicFormula_longValue() {
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(
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
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new StringAtomicFormula(
                                        AtomicFormula.VERSION_CODE,
                                        "test-value",
                                        /* isHashedValue= */ false));
        assertThat(e.getMessage()).matches(
                "Key VERSION_CODE cannot be used with StringAtomicFormula");
    }

    @Test
    public void testInvalidAtomicFormula_longValue() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new LongAtomicFormula(
                                        AtomicFormula.PACKAGE_NAME, AtomicFormula.EQ, 1));
        assertThat(e.getMessage()).matches(
                "Key PACKAGE_NAME cannot be used with LongAtomicFormula");
    }

    @Test
    public void testInvalidAtomicFormula_boolValue() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () -> new BooleanAtomicFormula(AtomicFormula.PACKAGE_NAME, true));
        assertThat(e.getMessage()).matches(
                "Key PACKAGE_NAME cannot be used with BooleanAtomicFormula");
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
        LongAtomicFormula formula =
                new LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GT, 1);
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);
        LongAtomicFormula newFormula =
                LongAtomicFormula.CREATOR.createFromParcel(p);

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
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () -> new LongAtomicFormula(/* key= */ -1,
                                AtomicFormula.EQ, /* value= */0));
        assertThat(e.getMessage()).matches("Unknown key: -1");
    }

    @Test
    public void testInvalidAtomicFormula_invalidOperator() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new LongAtomicFormula(
                                        AtomicFormula.VERSION_CODE, /* operator= */ -1, /* value= */
                                        0));
        assertThat(e.getMessage()).matches("Unknown operator: -1");
    }

    @Test
    public void testFormulaMatches_string_packageNameFormula_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */
                        false);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("com.test.app").build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_string_packageNameFormula_false() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */
                        false);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("com.foo.bar").build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_string_multipleAppCertificates_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.APP_CERTIFICATE, "cert", /* isHashedValue= */ true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder()
                        .setPackageName("com.test.app")
                        .setAppCertificates(Arrays.asList("test-cert", "cert"))
                        .build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_string_multipleAppCertificates_false() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.APP_CERTIFICATE, "cert", /* isHashedValue= */ true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder()
                        .setPackageName("com.test.app")
                        .setAppCertificates(Arrays.asList("test-cert", "another-cert"))
                        .build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_string_multipleInstallerCertificates_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.INSTALLER_CERTIFICATE, "cert", /* isHashedValue= */ true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder()
                        .setPackageName("com.test.app")
                        .setAppCertificates(Collections.singletonList("abc"))
                        .setInstallerCertificates(Arrays.asList("test-cert", "cert"))
                        .build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_string_multipleInstallerCertificates_false() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.INSTALLER_CERTIFICATE, "cert", /* isHashedValue= */ true);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder()
                        .setPackageName("com.test.app")
                        .setAppCertificates(Collections.singletonList("abc"))
                        .setInstallerCertificates(Arrays.asList("test-cert", "another-cert"))
                        .build();

        assertThat(stringAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testIsAppCertificateFormula_string_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.APP_CERTIFICATE, "cert", /* isHashedValue= */false);

        assertThat(stringAtomicFormula.isAppCertificateFormula()).isTrue();
    }

    @Test
    public void testIsAppCertificateFormula_string_false() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, "com.test.app", /* isHashedValue= */
                        false);

        assertThat(stringAtomicFormula.isAppCertificateFormula()).isFalse();
    }

    @Test
    public void testIsInstallerFormula_string_false() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.APP_CERTIFICATE, "cert", /* isHashedValue= */false);

        assertThat(stringAtomicFormula.isInstallerFormula()).isFalse();
    }

    @Test
    public void testIsInstallerFormula_string_installerName_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.INSTALLER_NAME,
                        "com.test.installer",
                        /* isHashedValue= */false);

        assertThat(stringAtomicFormula.isInstallerFormula()).isTrue();
    }

    @Test
    public void testIsInstallerFormula_string_installerCertificate_true() {
        StringAtomicFormula stringAtomicFormula =
                new StringAtomicFormula(
                        AtomicFormula.INSTALLER_CERTIFICATE, "cert", /* isHashedValue= */false);

        assertThat(stringAtomicFormula.isInstallerFormula()).isTrue();
    }

    @Test
    public void testFormulaMatches_long_eq_true() {
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_long_eq_false() {
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(1).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_long_gt_true() {
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GT,
                        0);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(1).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_long_gt_false() {
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.GT,
                        1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_long_gte_true() {
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(
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
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GTE, 1);
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setVersionCode(0).build();

        assertThat(longAtomicFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testIsAppCertificateFormula_long_false() {
        LongAtomicFormula longAtomicFormula =
                new AtomicFormula.LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GTE, 1);

        assertThat(longAtomicFormula.isAppCertificateFormula()).isFalse();
    }

    @Test
    public void testIsInstallerFormula_long_false() {
        LongAtomicFormula longAtomicFormula =
                new LongAtomicFormula(
                        AtomicFormula.VERSION_CODE, AtomicFormula.GTE, 1);

        assertThat(longAtomicFormula.isInstallerFormula()).isFalse();
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

    @Test
    public void testIsAppCertificateFormula_bool_false() {
        BooleanAtomicFormula boolFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);

        assertThat(boolFormula.isAppCertificateFormula()).isFalse();
    }

    @Test
    public void testIsInstallerFormula_bool_false() {
        BooleanAtomicFormula boolFormula =
                new BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true);

        assertThat(boolFormula.isInstallerFormula()).isFalse();
    }

    /** Returns a builder with all fields filled with some dummy data. */
    private AppInstallMetadata.Builder getAppInstallMetadataBuilder() {
        return new AppInstallMetadata.Builder()
                .setPackageName("abc")
                .setAppCertificates(Collections.singletonList("abc"))
                .setInstallerCertificates(Collections.singletonList("abc"))
                .setInstallerName("abc")
                .setVersionCode(-1)
                .setIsPreInstalled(true);
    }
}
