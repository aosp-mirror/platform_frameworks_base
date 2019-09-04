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

import static android.security.keystore.recovery.TrustedRootCertificates.getRootCertificates;

import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.cert.X509Certificate;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TrustedRootCertificatesTest {
    private static final String GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS =
            "GoogleCloudKeyVaultServiceV1";

    @Test
    public void getRootCertificates_listsGoogleCloudVaultV1Certificate() {
        Map<String, X509Certificate> certificates = getRootCertificates();

        assertTrue(certificates.containsKey(GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS));
    }
}
