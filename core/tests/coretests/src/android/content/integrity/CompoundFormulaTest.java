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
            new AtomicFormula.LongAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1);

    @Test
    public void testValidCompoundFormula() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));

        assertThat(compoundFormula.getConnector()).isEqualTo(CompoundFormula.AND);
        assertThat(compoundFormula.getFormulas()).containsAllOf(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2);
    }

    @Test
    public void testValidateAuxiliaryFormula_binaryConnectors() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new CompoundFormula(
                                        CompoundFormula.AND,
                                        Collections.singletonList(ATOMIC_FORMULA_1)));
        assertThat(e.getMessage()).matches("Connector AND must have at least 2 formulas");
    }

    @Test
    public void testValidateAuxiliaryFormula_unaryConnectors() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new CompoundFormula(
                                        CompoundFormula.NOT,
                                        Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2)));
        assertThat(e.getMessage()).matches("Connector NOT must have 1 formula only");
    }

    @Test
    public void testParcelUnparcel() {
        CompoundFormula formula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_2, ATOMIC_FORMULA_1));
        Parcel p = Parcel.obtain();
        formula.writeToParcel(p, 0);
        p.setDataPosition(0);

        assertThat(CompoundFormula.CREATOR.createFromParcel(p)).isEqualTo(formula);
    }

    @Test
    public void testInvalidCompoundFormula_invalidConnector() {
        Exception e =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new CompoundFormula(
                                        /* connector= */ -1,
                                        Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2)));
        assertThat(e.getMessage()).matches("Unknown connector: -1");
    }

    @Test
    public void testFormulaMatches_notFalse_true() {
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isFalse();

        CompoundFormula compoundFormula =
                new CompoundFormula(CompoundFormula.NOT, Arrays.asList(ATOMIC_FORMULA_1));
        assertThat(compoundFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_notTrue_false() {
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isTrue();

        CompoundFormula compoundFormula =
                new CompoundFormula(CompoundFormula.NOT, Arrays.asList(ATOMIC_FORMULA_1));
        assertThat(compoundFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_trueAndTrue_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(1).build();
        // validate assumptions about the metadata
        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isTrue();
        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isTrue();

        assertThat(compoundFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_trueAndFalse_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(2).build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isTrue();
        assertThat(ATOMIC_FORMULA_2.matches(appInstallMetadata)).isFalse();
        assertThat(compoundFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_falseAndTrue_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(1).build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isFalse();
        assertThat(ATOMIC_FORMULA_2.matches(appInstallMetadata)).isTrue();
        assertThat(compoundFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_falseAndFalse_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(2).build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isFalse();
        assertThat(ATOMIC_FORMULA_2.matches(appInstallMetadata)).isFalse();
        assertThat(compoundFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_trueOrTrue_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(1).build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isTrue();
        assertThat(ATOMIC_FORMULA_2.matches(appInstallMetadata)).isTrue();
        assertThat(compoundFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_trueOrFalse_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test1").setVersionCode(2).build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isTrue();
        assertThat(ATOMIC_FORMULA_2.matches(appInstallMetadata)).isFalse();
        assertThat(compoundFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_falseOrTrue_true() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(1).build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isFalse();
        assertThat(ATOMIC_FORMULA_2.matches(appInstallMetadata)).isTrue();
        assertThat(compoundFormula.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_falseOrFalse_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.OR, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));
        AppInstallMetadata appInstallMetadata =
                getAppInstallMetadataBuilder().setPackageName("test2").setVersionCode(2).build();

        assertThat(ATOMIC_FORMULA_1.matches(appInstallMetadata)).isFalse();
        assertThat(ATOMIC_FORMULA_2.matches(appInstallMetadata)).isFalse();
        assertThat(compoundFormula.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testIsAppCertificateFormula_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));

        assertThat(compoundFormula.isAppCertificateFormula()).isFalse();
    }

    @Test
    public void testIsAppCertificateFormula_true() {
        AtomicFormula appCertFormula =
                new AtomicFormula.StringAtomicFormula(AtomicFormula.APP_CERTIFICATE,
                        "app.cert", /* isHashed= */false);
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2, appCertFormula));

        assertThat(compoundFormula.isAppCertificateFormula()).isTrue();
    }

    @Test
    public void testIsInstallerFormula_false() {
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2));

        assertThat(compoundFormula.isInstallerFormula()).isFalse();
    }

    @Test
    public void testIsInstallerFormula_installerName_true() {
        AtomicFormula installerNameFormula =
                new AtomicFormula.StringAtomicFormula(AtomicFormula.INSTALLER_NAME,
                        "com.test.installer", /* isHashed= */false);
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2, installerNameFormula));

        assertThat(compoundFormula.isInstallerFormula()).isTrue();
    }

    @Test
    public void testIsInstallerFormula_installerCertificate_true() {
        AtomicFormula installerCertificateFormula =
                new AtomicFormula.StringAtomicFormula(AtomicFormula.INSTALLER_CERTIFICATE,
                        "cert", /* isHashed= */false);
        CompoundFormula compoundFormula =
                new CompoundFormula(
                        CompoundFormula.AND, Arrays.asList(ATOMIC_FORMULA_1, ATOMIC_FORMULA_2,
                        installerCertificateFormula));

        assertThat(compoundFormula.isInstallerFormula()).isTrue();
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
