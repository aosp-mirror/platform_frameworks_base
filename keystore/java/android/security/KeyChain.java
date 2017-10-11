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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.WorkerThread;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.keystore.AndroidKeyStoreProvider;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.android.org.conscrypt.TrustedCertificateStore;

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

    /**
     * @hide Also used by KeyChainService implementation
     */
    public static final String ACCOUNT_TYPE = "com.android.keychain";

    /**
     * Package name for KeyChain chooser.
     */
    private static final String KEYCHAIN_PACKAGE = "com.android.keychain";

    /**
     * Action to bring up the KeyChainActivity
     */
    private static final String ACTION_CHOOSER = "com.android.keychain.CHOOSER";

    /**
     * Package name for the Certificate Installer.
     */
    private static final String CERT_INSTALLER_PACKAGE = "com.android.certinstaller";

    /**
     * Extra for use with {@link #ACTION_CHOOSER}
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_RESPONSE = "response";

    /**
     * Extra for use with {@link #ACTION_CHOOSER}
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_URI = "uri";

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
     * Action to bring up the CertInstaller.
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
     * Broadcast Action: Indicates the trusted storage has changed. Sent when
     * one of this happens:
     *
     * <ul>
     * <li>a new CA is added,
     * <li>an existing CA is removed or disabled,
     * <li>a disabled CA is enabled,
     * <li>trusted storage is reset (all user certs are cleared),
     * <li>when permission to access a private key is changed.
     * </ul>
     *
     * @deprecated Use {@link #ACTION_KEYCHAIN_CHANGED}, {@link #ACTION_TRUST_STORE_CHANGED} or
     * {@link #ACTION_KEY_ACCESS_CHANGED}. Apps that target a version higher than
     * {@link Build.VERSION_CODES#N_MR1} will only receive this broadcast if they register for it
     * at runtime.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_STORAGE_CHANGED = "android.security.STORAGE_CHANGED";

    /**
     * Broadcast Action: Indicates the contents of the keychain has changed. Sent when a KeyChain
     * entry is added, modified or removed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_KEYCHAIN_CHANGED = "android.security.action.KEYCHAIN_CHANGED";

    /**
     * Broadcast Action: Indicates the contents of the trusted certificate store has changed. Sent
     * when one the following occurs:
     *
     * <ul>
     * <li>A pre-installed CA is disabled or re-enabled</li>
     * <li>A CA is added or removed from the trust store</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TRUST_STORE_CHANGED =
            "android.security.action.TRUST_STORE_CHANGED";

    /**
     * Broadcast Action: Indicates that the access permissions for a private key have changed.
     *
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_KEY_ACCESS_CHANGED =
            "android.security.action.KEY_ACCESS_CHANGED";

    /**
     * Used as a String extra field in {@link #ACTION_KEY_ACCESS_CHANGED} to supply the alias of
     * the key.
     */
    public static final String EXTRA_KEY_ALIAS = "android.security.extra.KEY_ALIAS";

    /**
     * Used as a boolean extra field in {@link #ACTION_KEY_ACCESS_CHANGED} to supply if the key is
     * accessible to the application.
     */
    public static final String EXTRA_KEY_ACCESSIBLE = "android.security.extra.KEY_ACCESSIBLE";

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
    @NonNull
    public static Intent createInstallIntent() {
        Intent intent = new Intent(ACTION_INSTALL);
        intent.setClassName(CERT_INSTALLER_PACKAGE,
                            "com.android.certinstaller.CertInstallerMain");
        return intent;
    }

    /**
     * Launches an {@code Activity} for the user to select the alias
     * for a private key and certificate pair for authentication. The
     * selected alias or null will be returned via the
     * KeyChainAliasCallback callback.
     *
     * <p>The device or profile owner can intercept this before the activity
     * is shown, to pick a specific private key alias.
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
    public static void choosePrivateKeyAlias(@NonNull Activity activity,
            @NonNull KeyChainAliasCallback response,
            @KeyProperties.KeyAlgorithmEnum String[] keyTypes, Principal[] issuers,
            @Nullable String host, int port, @Nullable String alias) {
        Uri uri = null;
        if (host != null) {
            uri = new Uri.Builder()
                    .authority(host + (port != -1 ? ":" + port : ""))
                    .build();
        }
        choosePrivateKeyAlias(activity, response, keyTypes, issuers, uri, alias);
    }

    /**
     * Launches an {@code Activity} for the user to select the alias
     * for a private key and certificate pair for authentication. The
     * selected alias or null will be returned via the
     * KeyChainAliasCallback callback.
     *
     * <p>The device or profile owner can intercept this before the activity
     * is shown, to pick a specific private key alias.</p>
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
     * @param activity The {@link Activity} context to use for
     *     launching the new sub-Activity to prompt the user to select
     *     a private key; used only to call startActivity(); must not
     *     be null.
     * @param response Callback to invoke when the request completes;
     *     must not be null
     * @param keyTypes The acceptable types of asymmetric keys such as
     *     "EC" or "RSA", or a null array.
     * @param issuers The acceptable certificate issuers for the
     *     certificate matching the private key, or null.
     * @param uri The full URI the server is requesting the certificate
     *     for, or null if unavailable.
     * @param alias The alias to preselect if available, or null if
     *     unavailable.
     */
    public static void choosePrivateKeyAlias(@NonNull Activity activity,
            @NonNull KeyChainAliasCallback response,
            @KeyProperties.KeyAlgorithmEnum String[] keyTypes, Principal[] issuers,
            @Nullable Uri uri, @Nullable String alias) {
        /*
         * TODO currently keyTypes, issuers are unused. They are meant
         * to follow the semantics and purpose of X509KeyManager
         * method arguments.
         *
         * keyTypes would allow the list to be filtered and typically
         * will be set correctly by the server. In practice today,
         * most all users will want only RSA or EC, and usually
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
        intent.setPackage(KEYCHAIN_PACKAGE);
        intent.putExtra(EXTRA_RESPONSE, new AliasResponse(response));
        intent.putExtra(EXTRA_URI, uri);
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
     * if there is no result.
     *
     * <p> This method may block while waiting for a connection to another process, and must never
     * be called from the main thread.
     * <p> As {@link Activity} and {@link Service} contexts are short-lived and can be destroyed
     * at any time from the main thread, it is safer to rely on a long-lived context such as one
     * returned from {@link Context#getApplicationContext()}.
     *
     * @param alias The alias of the desired private key, typically returned via
     *              {@link KeyChainAliasCallback#alias}.
     * @throws KeyChainException if the alias was valid but there was some problem accessing it.
     * @throws IllegalStateException if called from the main thread.
     */
    @Nullable @WorkerThread
    public static PrivateKey getPrivateKey(@NonNull Context context, @NonNull String alias)
            throws KeyChainException, InterruptedException {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }
        if (context == null) {
            throw new NullPointerException("context == null");
        }

        final String keyId;
        try (KeyChainConnection keyChainConnection = bind(context.getApplicationContext())) {
            keyId = keyChainConnection.getService().requestPrivateKey(alias);
        } catch (RemoteException e) {
            throw new KeyChainException(e);
        } catch (RuntimeException e) {
            // only certain RuntimeExceptions can be propagated across the IKeyChainService call
            throw new KeyChainException(e);
        }

        if (keyId == null) {
            return null;
        } else {
            try {
                return AndroidKeyStoreProvider.loadAndroidKeyStorePrivateKeyFromKeystore(
                        KeyStore.getInstance(), keyId, KeyStore.UID_SELF);
            } catch (RuntimeException | UnrecoverableKeyException e) {
                throw new KeyChainException(e);
            }
        }
    }

    /**
     * Returns the {@code X509Certificate} chain for the requested
     * alias, or null if there is no result.
     * <p>
     * <strong>Note:</strong> If a certificate chain was explicitly specified when the alias was
     * installed, this method will return that chain. If only the client certificate was specified
     * at the installation time, this method will try to build a certificate chain using all
     * available trust anchors (preinstalled and user-added).
     *
     * <p> This method may block while waiting for a connection to another process, and must never
     * be called from the main thread.
     * <p> As {@link Activity} and {@link Service} contexts are short-lived and can be destroyed
     * at any time from the main thread, it is safer to rely on a long-lived context such as one
     * returned from {@link Context#getApplicationContext()}.
     *
     * @param alias The alias of the desired certificate chain, typically
     * returned via {@link KeyChainAliasCallback#alias}.
     * @throws KeyChainException if the alias was valid but there was some problem accessing it.
     * @throws IllegalStateException if called from the main thread.
     */
    @Nullable @WorkerThread
    public static X509Certificate[] getCertificateChain(@NonNull Context context,
            @NonNull String alias) throws KeyChainException, InterruptedException {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        final byte[] certificateBytes;
        final byte[] certChainBytes;
        try (KeyChainConnection keyChainConnection = bind(context.getApplicationContext())) {
            IKeyChainService keyChainService = keyChainConnection.getService();
            certificateBytes = keyChainService.getCertificate(alias);
            if (certificateBytes == null) {
                return null;
            }
            certChainBytes = keyChainService.getCaCertificates(alias);
        } catch (RemoteException e) {
            throw new KeyChainException(e);
        } catch (RuntimeException e) {
            // only certain RuntimeExceptions can be propagated across the IKeyChainService call
            throw new KeyChainException(e);
        }

        try {
            X509Certificate leafCert = toCertificate(certificateBytes);
            // If the keypair is installed with a certificate chain by either
            // DevicePolicyManager.installKeyPair or CertInstaller, return that chain.
            if (certChainBytes != null && certChainBytes.length != 0) {
                Collection<X509Certificate> chain = toCertificates(certChainBytes);
                ArrayList<X509Certificate> fullChain = new ArrayList<>(chain.size() + 1);
                fullChain.add(leafCert);
                fullChain.addAll(chain);
                return fullChain.toArray(new X509Certificate[fullChain.size()]);
            } else {
                // If there isn't a certificate chain, either due to a pre-existing keypair
                // installed before N, or no chain is explicitly installed under the new logic,
                // fall back to old behavior of constructing the chain from trusted credentials.
                //
                // This logic exists to maintain old behaviour for already installed keypair, at
                // the cost of potentially returning extra certificate chain for new clients who
                // explicitly installed only the client certificate without a chain. The latter
                // case is actually no different from pre-N behaviour of getCertificateChain(),
                // in that sense this change introduces no regression. Besides the returned chain
                // is still valid so the consumer of the chain should have no problem verifying it.
                TrustedCertificateStore store = new TrustedCertificateStore();
                List<X509Certificate> chain = store.getCertificateChain(leafCert);
                return chain.toArray(new X509Certificate[chain.size()]);
            }
        } catch (CertificateException | RuntimeException e) {
            throw new KeyChainException(e);
        }
    }

    /**
     * Returns {@code true} if the current device's {@code KeyChain} supports a
     * specific {@code PrivateKey} type indicated by {@code algorithm} (e.g.,
     * "RSA").
     */
    public static boolean isKeyAlgorithmSupported(
            @NonNull @KeyProperties.KeyAlgorithmEnum String algorithm) {
        final String algUpper = algorithm.toUpperCase(Locale.US);
        return KeyProperties.KEY_ALGORITHM_EC.equals(algUpper)
                || KeyProperties.KEY_ALGORITHM_RSA.equals(algUpper);
    }

    /**
     * Returns {@code true} if the current device's {@code KeyChain} binds any
     * {@code PrivateKey} of the given {@code algorithm} to the device once
     * imported or generated. This can be used to tell if there is special
     * hardware support that can be used to bind keys to the device in a way
     * that makes it non-exportable.
     *
     * @deprecated Whether the key is bound to the secure hardware is known only
     * once the key has been imported. To find out, use:
     * <pre>{@code
     * PrivateKey key = ...; // private key from KeyChain
     *
     * KeyFactory keyFactory =
     *     KeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
     * KeyInfo keyInfo = keyFactory.getKeySpec(key, KeyInfo.class);
     * if (keyInfo.isInsideSecureHardware()) {
     *     // The key is bound to the secure hardware of this Android
     * }}</pre>
     */
    @Deprecated
    public static boolean isBoundKeyAlgorithm(
            @NonNull @KeyProperties.KeyAlgorithmEnum String algorithm) {
        if (!isKeyAlgorithmSupported(algorithm)) {
            return false;
        }

        return KeyStore.getInstance().isHardwareBacked(algorithm);
    }

    /** @hide */
    @NonNull
    public static X509Certificate toCertificate(@NonNull byte[] bytes) {
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

    /** @hide */
    @NonNull
    public static Collection<X509Certificate> toCertificates(@NonNull byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes == null");
        }
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (Collection<X509Certificate>) certFactory.generateCertificates(
                    new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * @hide for reuse by CertInstaller and Settings.
     * @see KeyChain#bind
     */
    public static class KeyChainConnection implements Closeable {
        private final Context context;
        private final ServiceConnection serviceConnection;
        private final IKeyChainService service;
        protected KeyChainConnection(Context context,
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
    @WorkerThread
    public static KeyChainConnection bind(@NonNull Context context) throws InterruptedException {
        return bindAsUser(context, Process.myUserHandle());
    }

    /**
     * @hide
     */
    @WorkerThread
    public static KeyChainConnection bindAsUser(@NonNull Context context, UserHandle user)
            throws InterruptedException {
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
                        q.put(IKeyChainService.Stub.asInterface(Binder.allowBlocking(service)));
                    } catch (InterruptedException e) {
                        // will never happen, since the queue starts with one available slot
                    }
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent(IKeyChainService.class.getName());
        ComponentName comp = intent.resolveSystemService(context.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !context.bindServiceAsUser(
                intent, keyChainServiceConnection, Context.BIND_AUTO_CREATE, user)) {
            throw new AssertionError("could not bind to KeyChainService");
        }
        return new KeyChainConnection(context, keyChainServiceConnection, q.take());
    }

    private static void ensureNotOnMainThread(@NonNull Context context) {
        Looper looper = Looper.myLooper();
        if (looper != null && looper == context.getMainLooper()) {
            throw new IllegalStateException(
                    "calling this from your main thread can lead to deadlock");
        }
    }
}
