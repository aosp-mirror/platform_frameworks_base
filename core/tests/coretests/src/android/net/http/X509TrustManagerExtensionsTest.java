/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.http;

import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.org.conscrypt.TrustManagerImpl;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class X509TrustManagerExtensionsTest {

    private class NotATrustManagerImpl implements X509TrustManager {

        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    @Test
    public void testBadCast() throws Exception {
        NotATrustManagerImpl ntmi = new NotATrustManagerImpl();
        try {
            X509TrustManagerExtensions tme = new X509TrustManagerExtensions(ntmi);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testGoodCast() throws Exception {
        String defaultType = KeyStore.getDefaultType();
        TrustManagerImpl tmi = new TrustManagerImpl(KeyStore.getInstance(defaultType));
        X509TrustManagerExtensions tme = new X509TrustManagerExtensions(tmi);
    }

    @Test
    public void testNormalUseCase() throws Exception {
        String defaultAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(defaultAlgorithm);
        String defaultKeystoreType = KeyStore.getDefaultType();
        tmf.init(KeyStore.getInstance(defaultKeystoreType));
        TrustManager[] tms = tmf.getTrustManagers();
        for (TrustManager tm : tms) {
            if (tm instanceof X509TrustManager) {
                new X509TrustManagerExtensions((X509TrustManager)tm);
                return;
            }
        }
        fail();
    }
}
