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
package android.security;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import libcore.util.Objects;
import org.apache.harmony.xnet.provider.jsse.TrustedCertificateStore;

/**
 * The {@code KeyChain} class provides access to private keys and
 * their corresponding certificate chains in credential storage.
 *
 * <p>Applications accessing the {@code KeyChain} normally go through
 * these steps:
 *
 * <ol>
 *
 * <li>Receive a callback from an {@link javax.net.ssl.X509KeyManager
 * X509KeyManager} that a private key is requested.
 *
 * <li>Call {@link #choosePrivateKeyAlias
 * choosePrivateKeyAlias} to allow the user to select from a
 * list of currently available private keys and corresponding
 * certificate chains. The chosen alias will be returned by the
 * callback {@link KeyChainAliasCallback#alias}, or null if no private
 * key is available or the user cancels the request.
 *
 * <li>Call {@link #getPrivateKey} and {@link #getCertificateChain} to
 * retrieve the credentials to return to the corresponding {@link
 * javax.net.ssl.X509KeyManager} callbacks.
 *
 * </ol>
 *
 * <p>An application may remember the value of a selected alias to
 * avoid prompting the user with {@link #choosePrivateKeyAlias
 * choosePrivateKeyAlias} on subsequent connections. If the alias is
 * no longer valid, null will be returned on lookups using that value
 *
 * <p>An application can request the installation of private keys and
 * certificates via the {@code Intent} provided by {@link
 * #createInstallIntent}. Private keys installed via this {@code
 * Intent} will be accessible via {@link #choosePrivateKeyAlias} while
 * Certificate Authority (CA) certificates will be trusted by all
 * applications through the default {@code X509TrustManager}.
 */
// TODO reference intent for credential installation when public
public final class KeyChain {

    private static final String TAG = "KeyChain";

    /**
     * @hide Also used by KeyChainService implementation
     */
    public static final String ACCOUNT_TYPE = "com.android.keychain";

    /**
     * Action to bring up the KeyChainActivity
     */
    private static final String ACTION_CHOOSER = "com.android.keychain.CHOOSER";

    /**
     * Extra for use with {@link #ACTION_CHOOSER}
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_RESPONSE = "response";

    /**
     * Extra for use with {@link #ACTION_CHOOSER}
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_HOST = "host";

    /**
     * Extra for use with {@link #ACTION_CHOOSER}
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_PORT = "port";

    /**
     * Extra for use with {@link #ACTION_CHOOSER}
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_ALIAS = "alias";

    /**
     * Extra for use with {@link #ACTION_CHOOSER}
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_SENDER = "sender";

    /**
     * Action to bring up the CertInstaller
     */
    private static final String ACTION_INSTALL = "android.credentials.INSTALL";

    /**
     * Optional extra to specify a {@code String} credential name on
     * the {@code Intent} returned by {@link #createInstallIntent}.
     */
    // Compatible with old com.android.certinstaller.CredentialHelper.CERT_NAME_KEY
    public static final String EXTRA_NAME = "name";

    /**
     * Optional extra to specify an X.509 certificate to install on
     * the {@code Intent} returned by {@link #createInstallIntent}.
     * The extra value should be a PEM or ASN.1 DER encoded {@code
     * byte[]}. An {@link X509Certificate} can be converted to DER
     * encoded bytes with {@link X509Certificate#getEncoded}.
     *
     * <p>{@link #EXTRA_NAME} may be used to provide a default alias
     * name for the installed certificate.
     */
    // Compatible with old android.security.Credentials.CERTIFICATE
    public static final String EXTRA_CERTIFICATE = "CERT";

    /**
     * Optional extra for use with the {@code Intent} returned by
     * {@link #createInstallIntent} to specify a PKCS#12 key store to
     * install. The extra value should be a {@code byte[]}. The bytes
     * may come from an external source or be generated with {@link
     * java.security.KeyStore#store} on a "PKCS12" instance.
     *
     * <p>The user will be prompted for the password to load the key store.
     *
     * <p>The key store will be scanned for {@link
     * java.security.KeyStore.PrivateKeyEntry} entries and both the
     * private key and associated certificate chain will be installed.
     *
     * <p>{@link #EXTRA_NAME} may be used to provide a default alias
     * name for the installed credentials.
     */
    // Compatible with old android.security.Credentials.PKCS12
    public static final String EXTRA_PKCS12 = "PKCS12";

    /**
     * Returns an {@code Intent} that can be used for credential
     * installation. The intent may be used without any extras, in
     * which case the user will be able to install credentials from
     * their own source.
     *
     * <p>Alternatively, {@link #EXTRA_CERTIFICATE} or {@link
     * #EXTRA_PKCS12} maybe used to specify the bytes of an X.509
     * certificate or a PKCS#12 key store for installation. These
     * extras may be combined with {@link #EXTRA_NAME} to provide a
     * default alias name for credentials being installed.
     *
     * <p>When used with {@link Activity#startActivityForResult},
     * {@link Activity#RESULT_OK} will be returned if a credential was
     * successfully installed, otherwise {@link
     * Activity#RESULT_CANCELED} will be returned.
     */
    public static Intent createInstallIntent() {
        Intent intent = new Intent(ACTION_INSTALL);
        intent.setClassName("com.android.certinstaller",
                            "com.android.certinstaller.CertInstallerMain");
        return intent;
    }

    /**
     * Launches an {@code Activity} for the user to select the alias
     * for a private key and certificate pair for authentication. The
     * selected alias or null will be returned via the
     * KeyChainAliasCallback callback.
     *
     * <p>{@code keyTypes} and {@code issuers} may be used to
     * highlight suggested choices to the user, although to cope with
     * sometimes erroneous values provided by servers, the user may be
     * able to override these suggestions.
     *
     * <p>{@code host} and {@code port} may be used to give the user
     * more context about the server requesting the credentials.
     *
     * <p>{@code alias} allows the chooser to preselect an existing
     * alias which will still be subject to user confirmation.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param activity The {@link Activity} context to use for
     *     launching the new sub-Activity to prompt the user to select
     *     a private key; used only to call startActivity(); must not
     *     be null.
     * @param response Callback to invoke when the request completes;
     *     must not be null
     * @param keyTypes The acceptable types of asymmetric keys such as
     *     "RSA" or "DSA", or a null array.
     * @param issuers The acceptable certificate issuers for the
     *     certificate matching the private key, or null.
     * @param host The host name of the server requesting the
     *     certificate, or null if unavailable.
     * @param port The port number of the server requesting the
     *     certificate, or -1 if unavailable.
     * @param alias The alias to preselect if available, or null if
     *     unavailable.
     */
    public static void choosePrivateKeyAlias(Activity activity, KeyChainAliasCallback response,
                                             String[] keyTypes, Principal[] issuers,
                                             String host, int port,
                                             String alias) {
        /*
         * TODO currently keyTypes, issuers are unused. They are meant
         * to follow the semantics and purpose of X509KeyManager
         * method arguments.
         *
         * keyTypes would allow the list to be filtered and typically
         * will be set correctly by the server. In practice today,
         * most all users will want only RSA, rarely DSA, and usually
         * only a small number of certs will be available.
         *
         * issuers is typically not useful. Some servers historically
         * will send the entire list of public CAs known to the
         * server. Others will send none. If this is used, if there
         * are no matches after applying the constraint, it should be
         * ignored.
         */
        if (activity == null) {
            throw new NullPointerException("activity == null");
        }
        if (response == null) {
            throw new NullPointerException("response == null");
        }
        Intent intent = new Intent(ACTION_CHOOSER);
        intent.putExtra(EXTRA_RESPONSE, new AliasResponse(response));
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_ALIAS, alias);
        // the PendingIntent is used to get calling package name
        intent.putExtra(EXTRA_SENDER, PendingIntent.getActivity(activity, 0, new Intent(), 0));
        activity.startActivity(intent);
    }

    private static class AliasResponse extends IKeyChainAliasCallback.Stub {
        private final KeyChainAliasCallback keyChainAliasResponse;
        private AliasResponse(KeyChainAliasCallback keyChainAliasResponse) {
            this.keyChainAliasResponse = keyChainAliasResponse;
        }
        @Override public void alias(String alias) {
            keyChainAliasResponse.alias(alias);
        }
    }

    /**
     * Returns the {@code PrivateKey} for the requested alias, or null
     * if no there is no result.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param alias The alias of the desired private key, typically
     * returned via {@link KeyChainAliasCallback#alias}.
     * @throws KeyChainException if the alias was valid but there was some problem accessing it.
     */
    public static PrivateKey getPrivateKey(Context context, String alias)
            throws KeyChainException, InterruptedException {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }
        KeyChainConnection keyChainConnection = bind(context);
        try {
            IKeyChainService keyChainService = keyChainConnection.getService();
            byte[] privateKeyBytes = keyChainService.getPrivateKey(alias);
            return toPrivateKey(privateKeyBytes);
        } catch (RemoteException e) {
            throw new KeyChainException(e);
        } catch (RuntimeException e) {
            // only certain RuntimeExceptions can be propagated across the IKeyChainService call
            throw new KeyChainException(e);
        } finally {
            keyChainConnection.close();
        }
    }

    /**
     * Returns the {@code X509Certificate} chain for the requested
     * alias, or null if no there is no result.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param alias The alias of the desired certificate chain, typically
     * returned via {@link KeyChainAliasCallback#alias}.
     * @throws KeyChainException if the alias was valid but there was some problem accessing it.
     */
    public static X509Certificate[] getCertificateChain(Context context, String alias)
            throws KeyChainException, InterruptedException {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }
        KeyChainConnection keyChainConnection = bind(context);
        try {
            IKeyChainService keyChainService = keyChainConnection.getService();
            byte[] certificateBytes = keyChainService.getCertificate(alias);
            List<X509Certificate> chain = new ArrayList<X509Certificate>();
            chain.add(toCertificate(certificateBytes));
            TrustedCertificateStore store = new TrustedCertificateStore();
            for (int i = 0; true; i++) {
                X509Certificate cert = chain.get(i);
                if (Objects.equal(cert.getSubjectX500Principal(), cert.getIssuerX500Principal())) {
                    break;
                }
                X509Certificate issuer = store.findIssuer(cert);
                if (issuer == null) {
                    break;
                }
                chain.add(issuer);
            }
            return chain.toArray(new X509Certificate[chain.size()]);
        } catch (RemoteException e) {
            throw new KeyChainException(e);
        } catch (RuntimeException e) {
            // only certain RuntimeExceptions can be propagated across the IKeyChainService call
            throw new KeyChainException(e);
        } finally {
            keyChainConnection.close();
        }
    }

    private static PrivateKey toPrivateKey(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes == null");
        }
        try {
            KeyPair keyPair = (KeyPair) Credentials.convertFromPem(bytes).get(0);
            return keyPair.getPrivate();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static X509Certificate toCertificate(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes == null");
        }
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(bytes));
            return (X509Certificate) cert;
        } catch (CertificateException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * @hide for reuse by CertInstaller and Settings.
     * @see KeyChain#bind
     */
    public final static class KeyChainConnection implements Closeable {
        private final Context context;
        private final ServiceConnection serviceConnection;
        private final IKeyChainService service;
        private KeyChainConnection(Context context,
                                   ServiceConnection serviceConnection,
                                   IKeyChainService service) {
            this.context = context;
            this.serviceConnection = serviceConnection;
            this.service = service;
        }
        @Override public void close() {
            context.unbindService(serviceConnection);
        }
        public IKeyChainService getService() {
            return service;
        }
    }

    /**
     * @hide for reuse by CertInstaller and Settings.
     *
     * Caller should call unbindService on the result when finished.
     */
    public static KeyChainConnection bind(Context context) throws InterruptedException {
        if (context == null) {
            throw new NullPointerException("context == null");
        }
        ensureNotOnMainThread(context);
        final BlockingQueue<IKeyChainService> q = new LinkedBlockingQueue<IKeyChainService>(1);
        ServiceConnection keyChainServiceConnection = new ServiceConnection() {
            volatile boolean mConnectedAtLeastOnce = false;
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (!mConnectedAtLeastOnce) {
                    mConnectedAtLeastOnce = true;
                    try {
                        q.put(IKeyChainService.Stub.asInterface(service));
                    } catch (InterruptedException e) {
                        // will never happen, since the queue starts with one available slot
                    }
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        boolean isBound = context.bindService(new Intent(IKeyChainService.class.getName()),
                                              keyChainServiceConnection,
                                              Context.BIND_AUTO_CREATE);
        if (!isBound) {
            throw new AssertionError("could not bind to KeyChainService");
        }
        return new KeyChainConnection(context, keyChainServiceConnection, q.take());
    }

    private static void ensureNotOnMainThread(Context context) {
        Looper looper = Looper.myLooper();
        if (looper != null && looper == context.getMainLooper()) {
            throw new IllegalStateException(
                    "calling this from your main thread can lead to deadlock");
        }
    }
}
