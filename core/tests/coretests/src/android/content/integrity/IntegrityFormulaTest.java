/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.content.integrity.IntegrityFormula.COMPOUND_FORMULA_TAG;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class IntegrityFormulaTest {

    @Test
    public void createEqualsFormula_packageName() {
        String packageName = "com.test.app";
        IntegrityFormula formula = IntegrityFormula.Application.packageNameEquals(packageName);

        AtomicFormula.StringAtomicFormula stringAtomicFormula =
                (AtomicFormula.StringAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.PACKAGE_NAME);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(packageName);
        assertThat(stringAtomicFormula.getIsHashedValue()).isFalse();
    }

    @Test
    public void createEqualsFormula_appCertificate() {
        String appCertificate = "com.test.app";
        IntegrityFormula formula = IntegrityFormula.Application.certificatesContain(appCertificate);

        AtomicFormula.StringAtomicFormula stringAtomicFormula =
                (AtomicFormula.StringAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.APP_CERTIFICATE);
        assertThat(stringAtomicFormula.getValue()).matches(appCertificate);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void createEqualsFormula_installerName() {
        String installerName = "com.test.app";
        IntegrityFormula formula = IntegrityFormula.Installer.packageNameEquals(installerName);

        AtomicFormula.StringAtomicFormula stringAtomicFormula =
                (AtomicFormula.StringAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.INSTALLER_NAME);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(installerName);
        assertThat(stringAtomicFormula.getIsHashedValue()).isFalse();
    }

    @Test
    public void createEqualsFormula_installerCertificate() {
        String installerCertificate = "com.test.app";
        IntegrityFormula formula =
                IntegrityFormula.Installer.certificatesContain(installerCertificate);

        AtomicFormula.StringAtomicFormula stringAtomicFormula =
                (AtomicFormula.StringAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.INSTALLER_CERTIFICATE);
        assertThat(stringAtomicFormula.getValue()).matches(installerCertificate);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }

    @Test
    public void createEqualsFormula_versionCode() {
        int versionCode = 12;
        IntegrityFormula formula = IntegrityFormula.Application.versionCodeEquals(versionCode);

        AtomicFormula.LongAtomicFormula stringAtomicFormula =
                (AtomicFormula.LongAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.VERSION_CODE);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(versionCode);
        assertThat(stringAtomicFormula.getOperator()).isEqualTo(AtomicFormula.EQ);
    }

    @Test
    public void createGreaterThanFormula_versionCode() {
        int versionCode = 12;
        IntegrityFormula formula = IntegrityFormula.Application.versionCodeGreaterThan(versionCode);

        AtomicFormula.LongAtomicFormula stringAtomicFormula =
                (AtomicFormula.LongAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.VERSION_CODE);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(versionCode);
        assertThat(stringAtomicFormula.getOperator()).isEqualTo(AtomicFormula.GT);
    }

    @Test
    public void createGreaterThanOrEqualsToFormula_versionCode() {
        int versionCode = 12;
        IntegrityFormula formula =
                IntegrityFormula.Application.versionCodeGreaterThanOrEqualTo(versionCode);

        AtomicFormula.LongAtomicFormula stringAtomicFormula =
                (AtomicFormula.LongAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.VERSION_CODE);
        assertThat(stringAtomicFormula.getValue()).isEqualTo(versionCode);
        assertThat(stringAtomicFormula.getOperator()).isEqualTo(AtomicFormula.GTE);
    }

    @Test
    public void createIsTrueFormula_preInstalled() {
        IntegrityFormula formula = IntegrityFormula.Application.isPreInstalled();

        AtomicFormula.BooleanAtomicFormula booleanAtomicFormula =
                (AtomicFormula.BooleanAtomicFormula) formula;

        assertThat(booleanAtomicFormula.getKey()).isEqualTo(AtomicFormula.PRE_INSTALLED);
        assertThat(booleanAtomicFormula.getValue()).isTrue();
    }

    @Test
    public void createAllFormula() {
        String packageName = "com.test.package";
        String certificateName = "certificate";
        IntegrityFormula formula1 = IntegrityFormula.Application.packageNameEquals(packageName);
        IntegrityFormula formula2 =
                IntegrityFormula.Application.certificatesContain(certificateName);

        IntegrityFormula compoundFormula = IntegrityFormula.all(formula1, formula2);

        assertThat(compoundFormula.getTag()).isEqualTo(COMPOUND_FORMULA_TAG);
    }

    @Test
    public void createAnyFormula() {
        String packageName = "com.test.package";
        String certificateName = "certificate";
        IntegrityFormula formula1 = IntegrityFormula.Application.packageNameEquals(packageName);
        IntegrityFormula formula2 =
                IntegrityFormula.Application.certificatesContain(certificateName);

        IntegrityFormula compoundFormula = IntegrityFormula.any(formula1, formula2);

        assertThat(compoundFormula.getTag()).isEqualTo(COMPOUND_FORMULA_TAG);
    }

    @Test
    public void createNotFormula() {
        String packageName = "com.test.package";

        IntegrityFormula compoundFormula =
                IntegrityFormula.not(IntegrityFormula.Application.packageNameEquals(packageName));

        assertThat(compoundFormula.getTag()).isEqualTo(COMPOUND_FORMULA_TAG);
    }

    @Test
    public void createIsTrueFormula_stampNotTrusted() {
        IntegrityFormula formula = IntegrityFormula.SourceStamp.notTrusted();

        AtomicFormula.BooleanAtomicFormula booleanAtomicFormula =
                (AtomicFormula.BooleanAtomicFormula) formula;

        assertThat(booleanAtomicFormula.getKey()).isEqualTo(AtomicFormula.STAMP_TRUSTED);
        assertThat(booleanAtomicFormula.getValue()).isFalse();
    }

    @Test
    public void createEqualsFormula_stampCertificateHash() {
        String stampCertificateHash = "test-cert";
        IntegrityFormula formula =
                IntegrityFormula.SourceStamp.stampCertificateHashEquals(stampCertificateHash);

        AtomicFormula.StringAtomicFormula stringAtomicFormula =
                (AtomicFormula.StringAtomicFormula) formula;

        assertThat(stringAtomicFormula.getKey()).isEqualTo(AtomicFormula.STAMP_CERTIFICATE_HASH);
        assertThat(stringAtomicFormula.getValue()).matches(stampCertificateHash);
        assertThat(stringAtomicFormula.getIsHashedValue()).isTrue();
    }
}
