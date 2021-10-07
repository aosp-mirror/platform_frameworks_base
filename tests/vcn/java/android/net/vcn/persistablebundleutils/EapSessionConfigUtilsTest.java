/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.vcn.persistablebundleutils;

import static android.telephony.TelephonyManager.APPTYPE_USIM;

import static org.junit.Assert.assertEquals;

import android.net.eap.EapSessionConfig;
import android.os.PersistableBundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class EapSessionConfigUtilsTest {
    private static final byte[] EAP_ID = "test@android.net".getBytes(StandardCharsets.US_ASCII);
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final int SUB_ID = 1;
    private static final String NETWORK_NAME = "android.net";
    private static final boolean ALLOW_MISMATCHED_NETWORK_NAMES = true;

    private EapSessionConfig.Builder createBuilderWithId() {
        return new EapSessionConfig.Builder().setEapIdentity(EAP_ID);
    }

    private static void verifyPersistableBundleEncodeDecodeIsLossless(EapSessionConfig config) {
        final PersistableBundle bundle = EapSessionConfigUtils.toPersistableBundle(config);
        final EapSessionConfig resultConfig = EapSessionConfigUtils.fromPersistableBundle(bundle);

        assertEquals(config, resultConfig);
    }

    @Test
    public void testSetEapMsChapV2EncodeDecodeIsLossless() throws Exception {
        final EapSessionConfig config =
                createBuilderWithId().setEapMsChapV2Config(USERNAME, PASSWORD).build();

        verifyPersistableBundleEncodeDecodeIsLossless(config);
    }

    @Test
    public void testSetEapSimEncodeDecodeIsLossless() throws Exception {
        final EapSessionConfig config =
                createBuilderWithId().setEapSimConfig(SUB_ID, APPTYPE_USIM).build();

        verifyPersistableBundleEncodeDecodeIsLossless(config);
    }

    @Test
    public void testSetEapAkaEncodeDecodeIsLossless() throws Exception {
        final EapSessionConfig config =
                createBuilderWithId().setEapAkaConfig(SUB_ID, APPTYPE_USIM).build();

        verifyPersistableBundleEncodeDecodeIsLossless(config);
    }

    @Test
    public void testSetEapAkaPrimeEncodeDecodeIsLossless() throws Exception {
        final EapSessionConfig config =
                createBuilderWithId()
                        .setEapAkaPrimeConfig(
                                SUB_ID, APPTYPE_USIM, NETWORK_NAME, ALLOW_MISMATCHED_NETWORK_NAMES)
                        .build();

        verifyPersistableBundleEncodeDecodeIsLossless(config);
    }

    @Test
    public void testSetEapTtlsEncodeDecodeIsLossless() throws Exception {
        final InputStream inputStream =
                InstrumentationRegistry.getContext()
                        .getResources()
                        .getAssets()
                        .open("self-signed-ca.pem");
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        final X509Certificate trustedCa =
                (X509Certificate) factory.generateCertificate(inputStream);

        final EapSessionConfig innerConfig =
                new EapSessionConfig.Builder().setEapMsChapV2Config(USERNAME, PASSWORD).build();

        final EapSessionConfig config =
                new EapSessionConfig.Builder().setEapTtlsConfig(trustedCa, innerConfig).build();

        verifyPersistableBundleEncodeDecodeIsLossless(config);
    }
}
