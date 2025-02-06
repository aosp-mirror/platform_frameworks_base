/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import static org.junit.Assert.fail;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

public final class TestUtils {

    private TestUtils() {
    }

    public static void assertConnectionFails(SSLContext context, String host, int port)
            throws Exception {
        try {
            SSLSocket s = (SSLSocket) context.getSocketFactory().createSocket(host, port);
            s.startHandshake();
            fail("Expected connection to " + host + ":" + port + " to fail.");
        } catch (SSLHandshakeException expected) {
        }
    }

    public static void assertConnectionSucceeds(SSLContext context, String host, int port)
            throws Exception {
        SSLSocket s = (SSLSocket) context.getSocketFactory().createSocket(host, port);
        s.startHandshake();
        s.getInputStream();
    }

    public static void assertUrlConnectionFails(SSLContext context, String host, int port)
            throws Exception {
        URL url = new URL("https://" + host + ":" + port);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setSSLSocketFactory(context.getSocketFactory());
        try {
            connection.getInputStream();
            fail("Connection to " + host + ":" + port + " expected to fail");
        } catch (SSLHandshakeException expected) {
            // ignored.
        }
    }

    public static void assertUrlConnectionSucceeds(SSLContext context, String host, int port)
            throws Exception {
        URL url = new URL("https://" + host + ":" + port);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.getInputStream();
    }

    public static SSLContext getSSLContext(ConfigSource source) throws Exception {
        ApplicationConfig config = new ApplicationConfig(source);
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance("PKIX", new NetworkSecurityConfigProvider());
        tmf.init(new RootTrustManagerFactorySpi.ApplicationConfigParameters(config));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    public static ApplicationInfo makeApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo();
        info.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        info.targetSandboxVersion = 1;
        return info;
    }

    public static ApplicationInfo makeApplicationInfo(int targetSdkVersion) {
        ApplicationInfo info = makeApplicationInfo();
        info.targetSdkVersion = targetSdkVersion;
        return info;
    }
}
