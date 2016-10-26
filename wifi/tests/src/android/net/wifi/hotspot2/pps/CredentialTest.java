/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi.hotspot2.pps;

import static org.junit.Assert.assertTrue;

import android.net.wifi.FakeKeys;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.CredentialTest}.
 */
@SmallTest
public class CredentialTest {
    private static Credential createCredential(Credential.UserCredential userCred,
                                               Credential.CertificateCredential certCred,
                                               Credential.SimCredential simCred,
                                               X509Certificate caCert,
                                               X509Certificate[] clientCertificateChain,
                                               PrivateKey clientPrivateKey) {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = userCred;
        cred.certCredential = certCred;
        cred.simCredential = simCred;
        cred.caCertificate = caCert;
        cred.clientCertificateChain = clientCertificateChain;
        cred.clientPrivateKey = clientPrivateKey;
        return cred;
    }

    private static Credential createCredentialWithCertificateCredential() {
        Credential.CertificateCredential certCred = new Credential.CertificateCredential();
        certCred.certType = "x509v3";
        certCred.certSha256FingerPrint = new byte[256];
        return createCredential(null, certCred, null, FakeKeys.CA_CERT0,
                new X509Certificate[] {FakeKeys.CLIENT_CERT}, FakeKeys.RSA_KEY1);
    }

    private static Credential createCredentialWithSimCredential() {
        Credential.SimCredential simCred = new Credential.SimCredential();
        simCred.imsi = "imsi";
        simCred.eapType = 1;
        return createCredential(null, null, simCred, null, null, null);
    }

    private static Credential createCredentialWithUserCredential() {
        Credential.UserCredential userCred = new Credential.UserCredential();
        userCred.username = "username";
        userCred.password = "password";
        userCred.eapType = 1;
        userCred.nonEapInnerMethod = "MS-CHAP";
        return createCredential(userCred, null, null, FakeKeys.CA_CERT0,
                new X509Certificate[] {FakeKeys.CLIENT_CERT}, FakeKeys.RSA_KEY1);
    }

    private static void verifyParcel(Credential writeCred) {
        Parcel parcel = Parcel.obtain();
        writeCred.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        Credential readCred = Credential.CREATOR.createFromParcel(parcel);
        assertTrue(readCred.equals(writeCred));
    }

    @Test
    public void verifyParcelWithDefault() throws Exception {
        verifyParcel(new Credential());
    }

    @Test
    public void verifyParcelWithCertificateCredential() throws Exception {
        verifyParcel(createCredentialWithCertificateCredential());
    }

    @Test
    public void verifyParcelWithSimCredential() throws Exception {
        verifyParcel(createCredentialWithSimCredential());
    }

    @Test
    public void verifyParcelWithUserCredential() throws Exception {
        verifyParcel(createCredentialWithUserCredential());
    }
}
