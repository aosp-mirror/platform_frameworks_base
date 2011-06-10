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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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
 */
// TODO reference intent for credential installation when public
public final class KeyChain {

    private static final String TAG = "KeyChain";

    /**
     * @hide Also used by KeyChainService implementation
     */
    public static final String ACCOUNT_TYPE = "com.android.keychain";

    /**
     * @hide Also used by KeyChainActivity implementation
     */
    public static final String EXTRA_RESPONSE = "response";

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
     */
    public static void choosePrivateKeyAlias(Activity activity, KeyChainAliasCallback response,
                                             String[] keyTypes, Principal[] issuers,
                                             String host, int port) {
        /*
         * TODO currently keyTypes, issuers, host, and port are
         * unused. They are meant to follow the semantics and purpose
         * of X509KeyManager method arguments.
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
         *
         * host and port may be shown to the user if available, but it
         * should be clear that they are not validated values, perhaps
         * shown along with requesting application identity to clarify
         * the source of the request.
         */
        if (activity == null) {
            throw new NullPointerException("activity == null");
        }
        if (response == null) {
            throw new NullPointerException("response == null");
        }
        Intent intent = new Intent("com.android.keychain.CHOOSER");
        intent.putExtra(EXTRA_RESPONSE, new AliasResponse(activity, response));
        activity.startActivity(intent);
    }

    private static class AliasResponse extends IKeyChainAliasCallback.Stub {
        private final Activity activity;
        private final KeyChainAliasCallback keyChainAliasResponse;
        private AliasResponse(Activity activity, KeyChainAliasCallback keyChainAliasResponse) {
            this.activity = activity;
            this.keyChainAliasResponse = keyChainAliasResponse;
        }
        @Override public void alias(String alias) {
            if (alias == null) {
                keyChainAliasResponse.alias(null);
                return;
            }
            AccountManager accountManager = AccountManager.get(activity);
            accountManager.getAuthToken(getAccount(activity),
                                        alias,
                                        null,
                                        activity,
                                        new AliasAccountManagerCallback(keyChainAliasResponse,
                                                                        alias),
                                        null);
        }
    }

    private static class AliasAccountManagerCallback implements AccountManagerCallback<Bundle> {
        private final KeyChainAliasCallback keyChainAliasResponse;
        private final String alias;
        private AliasAccountManagerCallback(KeyChainAliasCallback keyChainAliasResponse,
                                            String alias) {
            this.keyChainAliasResponse = keyChainAliasResponse;
            this.alias = alias;
        }
        @Override public void run(AccountManagerFuture<Bundle> future) {
            Bundle bundle;
            try {
                bundle = future.getResult();
            } catch (OperationCanceledException e) {
                keyChainAliasResponse.alias(null);
                return;
            } catch (IOException e) {
                keyChainAliasResponse.alias(null);
                return;
            } catch (AuthenticatorException e) {
                keyChainAliasResponse.alias(null);
                return;
            }
            String authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
            if (authToken != null) {
                keyChainAliasResponse.alias(alias);
            } else {
                keyChainAliasResponse.alias(null);
            }
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
            String authToken = authToken(context, alias);
            if (authToken == null) {
                return null;
            }
            IKeyChainService keyChainService = keyChainConnection.getService();
            byte[] privateKeyBytes = keyChainService.getPrivateKey(alias, authToken);
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
            String authToken = authToken(context, alias);
            if (authToken == null) {
                return null;
            }
            IKeyChainService keyChainService = keyChainConnection.getService();
            byte[] certificateBytes = keyChainService.getCertificate(alias, authToken);
            return new X509Certificate[] { toCertificate(certificateBytes) };
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

    private static String authToken(Context context, String alias) {
        AccountManager accountManager = AccountManager.get(context);
        AccountManagerFuture<Bundle> future = accountManager.getAuthToken(getAccount(context),
                                                                          alias,
                                                                          false,
                                                                          null,
                                                                          null);
        Bundle bundle;
        try {
            bundle = future.getResult();
        } catch (OperationCanceledException e) {
            throw new AssertionError(e);
        } catch (IOException e) {
            // KeyChainAccountAuthenticator doesn't do I/O
            throw new AssertionError(e);
        } catch (AuthenticatorException e) {
            throw new AssertionError(e);
        }
        Intent intent = bundle.getParcelable(AccountManager.KEY_INTENT);
        if (intent != null) {
            return null;
        }
        String authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
        if (authToken == null) {
            throw new AssertionError("Invalid authtoken");
        }
        return authToken;
    }

    private static Account getAccount(Context context) {
        AccountManager accountManager = AccountManager.get(context);
        Account[] accounts = accountManager.getAccountsByType(ACCOUNT_TYPE);
        if (accounts.length == 0) {
            try {
                // Account is created if necessary during binding of the IKeyChainService
                bind(context).close();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            accounts = accountManager.getAccountsByType(ACCOUNT_TYPE);
        }
        return accounts[0];
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
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    q.put(IKeyChainService.Stub.asInterface(service));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
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
