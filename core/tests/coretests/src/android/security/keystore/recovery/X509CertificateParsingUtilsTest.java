/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.security.keystore.recovery;

import static android.security.keystore.recovery.X509CertificateParsingUtils.decodeBase64Cert;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.cert.CertificateException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class X509CertificateParsingUtilsTest {

    private static final String GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_BASE64 = ""
            + "MIIFDzCCAvegAwIBAgIQbNdueU2o0vM9gGq4N6bhjzANBgkqhkiG9w0BAQsFADAx"
            + "MS8wLQYDVQQDEyZHb29nbGUgQ2xvdWQgS2V5IFZhdWx0IFNlcnZpY2UgUm9vdCBD"
            + "QTAeFw0xODA1MDcxODI0MDJaFw0zODA1MDgxOTI0MDJaMDExLzAtBgNVBAMTJkdv"
            + "b2dsZSBDbG91ZCBLZXkgVmF1bHQgU2VydmljZSBSb290IENBMIICIjANBgkqhkiG"
            + "9w0BAQEFAAOCAg8AMIICCgKCAgEArUgzu+4o9yl22eql1BiGBq3gWXooh2ql3J+v"
            + "Vuzf/ThjzdIg0xkkkw/NAFxYFi49Eo1fa/hf8wCIoAqCEs1lD6tE3cCD3T3+EQPq"
            + "uh6CB2KmZDJ6mPnXvVUlUuFr0O2MwZkwylqBETzK0x5NCHgL/p47vkjhHx6LqVao"
            + "bigKlHxszvVi4fkt/qq7KW3YTVxhwdLGEab+OqSfwMxdBLhMfE0K0dvFt8bs8yJA"
            + "F04DJsMbRChFFBpT17Z0u53iIAAu5qVQhKrQXiIAwgboZqd+JkHLXU1fJeVT5WJO"
            + "JgoJFWHkdWkHta4mSYlS72J1Q927JD1JdET1kFtH+EDtYAtx7x7F9xAAbb2tMITw"
            + "s/wwd2rAzZTX/kxRbDlXVLToU05LFYPr+dFV1wvXmi0jlkIxnhdaVBqWC93p528U"
            + "iUcLpib+HVzMWGdYI3G1NOa/lTp0c8LcbJjapiiVneRQJ3cIqDPOSEnEq40hyZd1"
            + "jx3JnOxJMwHs8v4s9GIlb3BcOmDvA/Mu09xEMKwpHBm4TFDKXeGHOWha7ccWEECb"
            + "yO5ncu6XuN2iyz9S+TuMyjZBE552p6Pu5gEC2xk+qab0NGDTHdLKLbyWn3IxdmBH"
            + "yTr7iPCqmpyHngkC/pbGfvGusc5BpBugsBtlz67m4RWLJ72yAeVPO/ly/8w4orNs"
            + "GWjn3s0CAwEAAaMjMCEwDgYDVR0PAQH/BAQDAgGGMA8GA1UdEwEB/wQFMAMBAf8w"
            + "DQYJKoZIhvcNAQELBQADggIBAGiWlu+4qyxgPb6RsA0mwR7V21UJ9rEpYhSN+ARp"
            + "TWGiI22RCJSGK0ZrPGeFQzE2BpnVRdmLTV5jf9JUStjHoPvNYFnwLTJ0E2e9Olj8"
            + "MrHrAucAUFLhl4woWz0kU/X0EB1j6Y2SXrAaZPiMMpq8BKj3mH1MbV4stZ0kiHUp"
            + "Zu6PEmrojYG7FKKN30na2xXfiOfl2JusVsyHDqmUn/HjTh6zASKqE6hxE+FJRl2V"
            + "Q4dcr4SviHtdbimMy2LghLnZ4FE4XhJgRnw9TeRV5C9Sn7pmnAA5X0C8ZXhXvfvr"
            + "dx4fL3UKlk1Lqlb5skxoK1R9wwr+aNIO+cuR8JA5DmEDWFw5Budh/uWWZlBTyVW2"
            + "ybbTB6tkmOc8c08XOgxBaKrsXALmJcluabjmN1jp81ae1epeN31jJ4N5IE5aq7Xb"
            + "TFmKkwpgTTvJmqCR2XzWujlvdbdjfiABliWsnLzLQCP8eZwcM4LA5UK3f1ktHolr"
            + "1OI9etSOkebE2py8LPYBJWlX36tRAagZhU/NoyOtvhRzq9rb3rbf96APEHKUFsXG"
            + "9nBEd2BUKZghLKPf+JNCU/2pOGx0jdMcf+K+a1DeG0YzGYMRkFvpN3hvHYrJdByL"
            + "3kSP3UtD0H2g8Ps7gRLELG2HODxbSn8PV3XtuSvxVanA6uyaaS3AZ6SxeVLvmw50"
            + "7aYI";

    private static final String INVALID_BASE64 = "EVTVA==";

    @Test
    public void decodeBase64Cert_decodesValidCertificate() throws Exception {
        assertNotNull(decodeBase64Cert(GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_BASE64));
    }

    @Test
    public void decodeBase64Cert_throwsCertificateExceptionForInvalidBase64() {
        try {
            decodeBase64Cert(INVALID_BASE64);
            fail("Did not throw when attempting to decode invalid base-64");
        } catch (CertificateException e) {
            // expected
        }
    }
}
