/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.net;

import com.android.org.conscrypt.ClientSessionContext;
import com.android.org.conscrypt.SSLClientSessionCache;

import org.mockito.Mockito;

import junit.framework.TestCase;

import java.security.KeyManagementException;
import java.security.SecureRandom;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

public class SSLSessionCacheTest extends TestCase {

    public void testInstall_compatibleContext() throws Exception {
        final SSLContext ctx = SSLContext.getDefault();
        final SSLClientSessionCache mock = Mockito.mock(SSLClientSessionCache.class);
        final ClientSessionContext clientCtx = (ClientSessionContext) ctx.getClientSessionContext();

        try {
            SSLSessionCache.install(new SSLSessionCache(mock), ctx);
        } finally {
            // Restore cacheless behaviour.
            SSLSessionCache.install(null, ctx);
            Mockito.verifyNoMoreInteractions(mock);
        }
    }

    public void testInstall_incompatibleContext() {
        try {
            SSLSessionCache.install(
                    new SSLSessionCache(Mockito.mock(SSLClientSessionCache.class)),
                    new FakeSSLContext());
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    static final class FakeSSLContext extends SSLContext {
        protected FakeSSLContext() {
            super(new FakeSSLContextSpi(), null, "test");
        }
    }

    static final class FakeSSLContextSpi extends SSLContextSpi {
        @Override
        protected void engineInit(KeyManager[] keyManagers, TrustManager[] trustManagers,
                SecureRandom secureRandom) throws KeyManagementException {
        }

        @Override
        protected SSLSocketFactory engineGetSocketFactory() {
            return null;
        }

        @Override
        protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return null;
        }

        @Override
        protected SSLEngine engineCreateSSLEngine(String s, int i) {
            return null;
        }

        @Override
        protected SSLEngine engineCreateSSLEngine() {
            return null;
        }

        @Override
        protected SSLSessionContext engineGetServerSessionContext() {
            return null;
        }

        @Override
        protected SSLSessionContext engineGetClientSessionContext() {
            return Mockito.mock(SSLSessionContext.class);
        }
    }
}
