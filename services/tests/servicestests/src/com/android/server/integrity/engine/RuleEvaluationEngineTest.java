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

package com.android.server.integrity.engine;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.IntegrityFormula;
import android.content.integrity.Rule;

import com.android.server.integrity.IntegrityFileManager;
import com.android.server.integrity.model.IntegrityCheckResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(JUnit4.class)
public class RuleEvaluationEngineTest {

    private static final String INSTALLER_1 = "installer1";
    private static final String INSTALLER_1_CERT = "installer1_cert";
    private static final String INSTALLER_2 = "installer2";
    private static final String INSTALLER_2_CERT = "installer2_cert";

    private static final String RANDOM_INSTALLER = "random";
    private static final String RANDOM_INSTALLER_CERT = "random_cert";

    @Mock
    private IntegrityFileManager mIntegrityFileManager;

    private RuleEvaluationEngine mEngine;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mEngine = new RuleEvaluationEngine(mIntegrityFileManager);

        when(mIntegrityFileManager.readRules(any())).thenReturn(Collections.singletonList(new Rule(
                IntegrityFormula.Installer.notAllowedByManifest(), Rule.DENY)));

        when(mIntegrityFileManager.initialized()).thenReturn(true);
    }

    @Test
    public void testAllowedInstallers_empty() {
        AppInstallMetadata appInstallMetadata1 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_1)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_1_CERT))
                        .build();
        AppInstallMetadata appInstallMetadata2 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_2)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_2_CERT))
                        .build();
        AppInstallMetadata appInstallMetadata3 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(RANDOM_INSTALLER)
                        .setInstallerCertificates(Collections.singletonList(RANDOM_INSTALLER_CERT))
                        .build();

        assertThat(mEngine.evaluate(appInstallMetadata1).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.ALLOW);
        assertThat(mEngine.evaluate(appInstallMetadata2).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.ALLOW);
        assertThat(mEngine.evaluate(appInstallMetadata3).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.ALLOW);
    }

    @Test
    public void testAllowedInstallers_oneElement() {
        Map<String, String> allowedInstallers =
                Collections.singletonMap(INSTALLER_1, INSTALLER_1_CERT);

        AppInstallMetadata appInstallMetadata1 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_1)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_1_CERT))
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata1).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.ALLOW);

        AppInstallMetadata appInstallMetadata2 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(RANDOM_INSTALLER)
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_1_CERT))
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata2).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.DENY);

        AppInstallMetadata appInstallMetadata3 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_1)
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .setInstallerCertificates(Collections.singletonList(RANDOM_INSTALLER_CERT))
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata3).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.DENY);

        AppInstallMetadata appInstallMetadata4 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_1)
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .setInstallerCertificates(Collections.singletonList(RANDOM_INSTALLER_CERT))
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata4).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.DENY);
    }

    @Test
    public void testAllowedInstallers_multipleElement() {
        Map<String, String> allowedInstallers = new HashMap<>(2);
        allowedInstallers.put(INSTALLER_1, INSTALLER_1_CERT);
        allowedInstallers.put(INSTALLER_2, INSTALLER_2_CERT);

        AppInstallMetadata appInstallMetadata1 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_1)
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_1_CERT))
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata1).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.ALLOW);

        AppInstallMetadata appInstallMetadata2 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_2)
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_2_CERT))
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata2).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.ALLOW);

        AppInstallMetadata appInstallMetadata3 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_1)
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_2_CERT))
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata3).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.DENY);

        AppInstallMetadata appInstallMetadata4 =
                getAppInstallMetadataBuilder()
                        .setInstallerName(INSTALLER_2)
                        .setAllowedInstallersAndCert(allowedInstallers)
                        .setInstallerCertificates(Collections.singletonList(INSTALLER_1_CERT))
                        .build();
        assertThat(mEngine.evaluate(appInstallMetadata4).getEffect())
                .isEqualTo(IntegrityCheckResult.Effect.DENY);
    }

    /** Returns a builder with all fields filled with some placeholder data. */
    private AppInstallMetadata.Builder getAppInstallMetadataBuilder() {
        return new AppInstallMetadata.Builder()
                .setPackageName("abc")
                .setAppCertificates(Collections.singletonList("abc"))
                .setAppCertificateLineage(Collections.singletonList("abc"))
                .setInstallerCertificates(Collections.singletonList("abc"))
                .setInstallerName("abc")
                .setVersionCode(-1)
                .setIsPreInstalled(true);
    }
}
