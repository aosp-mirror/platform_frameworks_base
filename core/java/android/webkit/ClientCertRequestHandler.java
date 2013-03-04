/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.webkit;

import android.os.Handler;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import org.apache.harmony.xnet.provider.jsse.NativeCrypto;
import org.apache.harmony.xnet.provider.jsse.OpenSSLKey;
import org.apache.harmony.xnet.provider.jsse.OpenSSLKeyHolder;

/**
 * ClientCertRequestHandler: class responsible for handling client
 * certificate requests.  This class is passed as a parameter to
 * BrowserCallback.displayClientCertRequestDialog and is meant to
 * receive the user's response.
 *
 * @hide
 */
public final class ClientCertRequestHandler extends Handler {

    private final BrowserFrame mBrowserFrame;
    private final int mHandle;
    private final String mHostAndPort;
    private final SslClientCertLookupTable mTable;
    ClientCertRequestHandler(BrowserFrame browserFrame,
                             int handle,
                             String host_and_port,
                             SslClientCertLookupTable table) {
        mBrowserFrame = browserFrame;
        mHandle = handle;
        mHostAndPort = host_and_port;
        mTable = table;
    }

    /**
     * Proceed with the specified private key and client certificate chain.
     */
    public void proceed(PrivateKey privateKey, X509Certificate[] chain) {
        try {
            byte[][] chainBytes = NativeCrypto.encodeCertificates(chain);
            mTable.Allow(mHostAndPort, privateKey, chainBytes);

            if (privateKey instanceof OpenSSLKeyHolder) {
                OpenSSLKey pkey = ((OpenSSLKeyHolder) privateKey).getOpenSSLKey();
                setSslClientCertFromCtx(pkey.getPkeyContext(), chainBytes);
            } else {
                setSslClientCertFromPKCS8(privateKey.getEncoded(), chainBytes);
            }
        } catch (CertificateEncodingException e) {
            post(new Runnable() {
                    public void run() {
                        mBrowserFrame.nativeSslClientCert(mHandle, 0, null);
                        return;
                    }
                });
        }
    }

    /**
     * Proceed with the specified private key bytes and client certificate chain.
     */
    private void setSslClientCertFromCtx(final long ctx, final byte[][] chainBytes) {
        post(new Runnable() {
                public void run() {
                    mBrowserFrame.nativeSslClientCert(mHandle, ctx, chainBytes);
                }
            });
    }

    /**
     * Proceed with the specified private key context and client certificate chain.
     */
    private void setSslClientCertFromPKCS8(final byte[] key, final byte[][] chainBytes) {
        post(new Runnable() {
                public void run() {
                    mBrowserFrame.nativeSslClientCert(mHandle, key, chainBytes);
                }
            });
    }

    /**
     * Igore the request for now, the user may be prompted again.
     */
    public void ignore() {
        post(new Runnable() {
                public void run() {
                    mBrowserFrame.nativeSslClientCert(mHandle, 0, null);
                }
            });
    }

    /**
     * Cancel this request, remember the users negative choice.
     */
    public void cancel() {
        mTable.Deny(mHostAndPort);
        post(new Runnable() {
                public void run() {
                    mBrowserFrame.nativeSslClientCert(mHandle, 0, null);
                }
            });
    }
}
