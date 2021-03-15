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

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.telephony.TelephonyManager.APPTYPE_USIM;

import static org.junit.Assert.assertEquals;

import android.net.InetAddresses;
import android.net.eap.EapSessionConfig;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeSessionParams;
import android.os.PersistableBundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.org.bouncycastle.util.io.pem.PemObject;
import com.android.internal.org.bouncycastle.util.io.pem.PemReader;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IkeSessionParamsUtilsTest {
    private static IkeSessionParams.Builder createBuilderMinimum() {
        final InetAddress serverAddress = InetAddresses.parseNumericAddress("192.0.2.100");

        return new IkeSessionParams.Builder()
                .setServerHostname(serverAddress.getHostAddress())
                .addSaProposal(SaProposalUtilsTest.buildTestIkeSaProposal())
                .setLocalIdentification(new IkeFqdnIdentification("client.test.android.net"))
                .setRemoteIdentification(new IkeFqdnIdentification("server.test.android.net"))
                .setAuthPsk("psk".getBytes());
    }

    private static void verifyPersistableBundleEncodeDecodeIsLossless(IkeSessionParams params) {
        final PersistableBundle bundle = IkeSessionParamsUtils.toPersistableBundle(params);
        final IkeSessionParams result = IkeSessionParamsUtils.fromPersistableBundle(bundle);

        assertEquals(result, params);
    }

    @Test
    public void testEncodeRecodeParamsWithLifetimes() throws Exception {
        final int hardLifetime = (int) TimeUnit.HOURS.toSeconds(20L);
        final int softLifetime = (int) TimeUnit.HOURS.toSeconds(10L);
        final IkeSessionParams params =
                createBuilderMinimum().setLifetimeSeconds(hardLifetime, softLifetime).build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithDpdDelay() throws Exception {
        final int dpdDelay = (int) TimeUnit.MINUTES.toSeconds(10L);
        final IkeSessionParams params = createBuilderMinimum().setDpdDelaySeconds(dpdDelay).build();

        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithNattKeepalive() throws Exception {
        final int nattKeepAliveDelay = (int) TimeUnit.MINUTES.toSeconds(5L);
        final IkeSessionParams params =
                createBuilderMinimum().setNattKeepAliveDelaySeconds(nattKeepAliveDelay).build();

        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithRetransmissionTimeouts() throws Exception {
        final int[] retransmissionTimeout = new int[] {500, 500, 500, 500, 500, 500};
        final IkeSessionParams params =
                createBuilderMinimum()
                        .setRetransmissionTimeoutsMillis(retransmissionTimeout)
                        .build();

        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithConfigRequests() throws Exception {
        final Inet4Address ipv4Address =
                (Inet4Address) InetAddresses.parseNumericAddress("192.0.2.100");
        final Inet6Address ipv6Address =
                (Inet6Address) InetAddresses.parseNumericAddress("2001:db8::1");

        final IkeSessionParams params =
                createBuilderMinimum()
                        .addPcscfServerRequest(AF_INET)
                        .addPcscfServerRequest(AF_INET6)
                        .addPcscfServerRequest(ipv4Address)
                        .addPcscfServerRequest(ipv6Address)
                        .build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithAuthPsk() throws Exception {
        final IkeSessionParams params = createBuilderMinimum().setAuthPsk("psk".getBytes()).build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithIkeOptions() throws Exception {
        final IkeSessionParams params =
                createBuilderMinimum()
                        .addIkeOption(IkeSessionParams.IKE_OPTION_ACCEPT_ANY_REMOTE_ID)
                        .addIkeOption(IkeSessionParams.IKE_OPTION_MOBIKE)
                        .build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    private static InputStream openAssetsFile(String fileName) throws Exception {
        return InstrumentationRegistry.getContext().getResources().getAssets().open(fileName);
    }

    private static X509Certificate createCertFromPemFile(String fileName) throws Exception {
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(openAssetsFile(fileName));
    }

    private static RSAPrivateKey createRsaPrivateKeyFromKeyFile(String fileName) throws Exception {
        final PemObject pemObject =
                new PemReader(new InputStreamReader(openAssetsFile(fileName))).readPemObject();
        return (RSAPrivateKey) CertUtils.privateKeyFromByteArray(pemObject.getContent());
    }

    @Test
    public void testEncodeRecodeParamsWithDigitalSignAuth() throws Exception {
        final X509Certificate serverCaCert = createCertFromPemFile("self-signed-ca.pem");
        final X509Certificate clientEndCert = createCertFromPemFile("client-end-cert.pem");
        final RSAPrivateKey clientPrivateKey =
                createRsaPrivateKeyFromKeyFile("client-private-key.key");

        final IkeSessionParams params =
                createBuilderMinimum()
                        .setAuthDigitalSignature(serverCaCert, clientEndCert, clientPrivateKey)
                        .build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }

    @Test
    public void testEncodeRecodeParamsWithEapAuth() throws Exception {
        final X509Certificate serverCaCert = createCertFromPemFile("self-signed-ca.pem");

        final byte[] eapId = "test@android.net".getBytes(StandardCharsets.US_ASCII);
        final int subId = 1;
        final EapSessionConfig eapConfig =
                new EapSessionConfig.Builder()
                        .setEapIdentity(eapId)
                        .setEapSimConfig(subId, APPTYPE_USIM)
                        .setEapAkaConfig(subId, APPTYPE_USIM)
                        .build();

        final IkeSessionParams params =
                createBuilderMinimum().setAuthEap(serverCaCert, eapConfig).build();
        verifyPersistableBundleEncodeDecodeIsLossless(params);
    }
}
