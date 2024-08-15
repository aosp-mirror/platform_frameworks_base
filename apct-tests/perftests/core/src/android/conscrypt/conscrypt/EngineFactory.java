/*
 * Copyright 2017 The Android Open Source Project
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
package android.conscrypt;

import org.conscrypt.TestUtils;
import java.security.Security;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;


/**
 * Factory for {@link SSLEngine} instances.
 */
public class EngineFactory {
    public EngineFactory() {
        this(newConscryptClientContext(), newConscryptServerContext());
    }

    private EngineFactory(SSLContext clientContext, SSLContext serverContext) {
        this.clientContext = clientContext;
        this.serverContext = serverContext;
    }

    private final SSLContext clientContext;
    private final SSLContext serverContext;

    public SSLEngine newClientEngine(String cipher) {
        SSLEngine engine = initEngine(clientContext.createSSLEngine(), cipher, true);
        return engine;
    }

    public SSLEngine newServerEngine(String cipher) {
        SSLEngine engine = initEngine(serverContext.createSSLEngine(), cipher, false);
        return engine;
    }

    public void dispose(SSLEngine engine) {
        engine.closeOutbound();
    }

    private static SSLContext newConscryptClientContext() {
        return TestUtils.newClientSslContext(TestUtils.getConscryptProvider());
    }

    private static SSLContext newConscryptServerContext() {
        return TestUtils.newServerSslContext(TestUtils.getConscryptProvider());
    }

    static SSLEngine initEngine(SSLEngine engine, String cipher, boolean client) {
        engine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        engine.setEnabledCipherSuites(new String[] {cipher});
        engine.setUseClientMode(client);
        return engine;
    }
}