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

import static android.content.integrity.InstallerAllowedByManifestFormula.INSTALLER_CERTIFICATE_NOT_EVALUATED;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

@RunWith(JUnit4.class)
public class InstallerAllowedByManifestFormulaTest {

    private static final InstallerAllowedByManifestFormula FORMULA =
            new InstallerAllowedByManifestFormula();

    @Test
    public void testFormulaMatches_installerAndCertBothInManifest() {
        AppInstallMetadata appInstallMetadata = getAppInstallMetadataBuilder()
                .setInstallerName("installer1")
                .setInstallerCertificates(Arrays.asList("installer_cert1", "random_cert"))
                .setAllowedInstallersAndCert(ImmutableMap.of(
                        "installer1", "installer_cert1",
                        "installer2", "installer_cert2"
                )).build();

        assertThat(FORMULA.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_installerAndCertDoesNotMatchInManifest() {
        AppInstallMetadata appInstallMetadata = getAppInstallMetadataBuilder()
                .setInstallerName("installer1")
                .setInstallerCertificates(Arrays.asList("installer_cert1", "random_cert"))
                .setAllowedInstallersAndCert(ImmutableMap.of(
                        "installer1", "installer_cert2",
                        "installer2", "installer_cert1"
                )).build();

        assertThat(FORMULA.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_installerNotInManifest() {
        AppInstallMetadata appInstallMetadata = getAppInstallMetadataBuilder()
                .setInstallerName("installer3")
                .setInstallerCertificates(Arrays.asList("installer_cert1", "random_cert"))
                .setAllowedInstallersAndCert(ImmutableMap.of(
                        "installer1", "installer_cert2",
                        "installer2", "installer_cert1"
                )).build();

        assertThat(FORMULA.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_certificateDoesNotMatchManifest() {
        AppInstallMetadata appInstallMetadata = getAppInstallMetadataBuilder()
                .setInstallerName("installer1")
                .setInstallerCertificates(Arrays.asList("installer_cert3", "random_cert"))
                .setAllowedInstallersAndCert(ImmutableMap.of(
                        "installer1", "installer_cert2",
                        "installer2", "installer_cert1"
                )).build();

        assertThat(FORMULA.matches(appInstallMetadata)).isFalse();
    }

    @Test
    public void testFormulaMatches_emptyManifest() {
        AppInstallMetadata appInstallMetadata = getAppInstallMetadataBuilder()
                .setInstallerName("installer1")
                .setInstallerCertificates(Arrays.asList("installer_cert3", "random_cert"))
                .setAllowedInstallersAndCert(ImmutableMap.of()).build();

        assertThat(FORMULA.matches(appInstallMetadata)).isTrue();
    }

    @Test
    public void testFormulaMatches_certificateNotSpecifiedInManifest() {
        AppInstallMetadata appInstallMetadata = getAppInstallMetadataBuilder()
                .setInstallerName("installer1")
                .setInstallerCertificates(Arrays.asList("installer_cert3", "random_cert"))
                .setAllowedInstallersAndCert(ImmutableMap.of(
                        "installer1", INSTALLER_CERTIFICATE_NOT_EVALUATED,
                        "installer2", "installer_cert1"
                )).build();

        assertThat(FORMULA.matches(appInstallMetadata)).isTrue();
    }

    /** Returns a builder with all fields filled with some placeholder data. */
    private AppInstallMetadata.Builder getAppInstallMetadataBuilder() {
        return new AppInstallMetadata.Builder()
                .setPackageName("abc")
                .setAppCertificates(Collections.emptyList())
                .setAppCertificateLineage(Collections.emptyList())
                .setInstallerCertificates(Collections.emptyList())
                .setInstallerName("abc")
                .setVersionCode(-1)
                .setIsPreInstalled(true);
    }
}
