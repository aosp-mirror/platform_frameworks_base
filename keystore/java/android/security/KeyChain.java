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
import java.security.NoSuchAlgorithmException;
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
 * @hide
 */
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
     * IKeyChainAliasResponse callback.
     */
    public static void choosePrivateKeyAlias(Activity activity, KeyChainAliasResponse response) {
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

    private static class AliasResponse extends IKeyChainAliasResponse.Stub {
        private final Activity activity;
        private final KeyChainAliasResponse keyChainAliasResponse;
        private AliasResponse(Activity activity, KeyChainAliasResponse keyChainAliasResponse) {
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
        private final KeyChainAliasResponse keyChainAliasResponse;
        private final String alias;
        private AliasAccountManagerCallback(KeyChainAliasResponse keyChainAliasResponse,
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
     */
    public static PrivateKey getPrivateKey(Context context, String alias)
            throws InterruptedException, RemoteException {
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
        } finally {
            keyChainConnection.close();
        }
    }

    /**
     * Returns the {@code X509Certificate} chain for the requested
     * alias, or null if no there is no result.
     */
    public static X509Certificate[] getCertificateChain(Context context, String alias)
            throws InterruptedException, RemoteException {
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
        } finally {
            keyChainConnection.close();
        }
    }

    private static PrivateKey toPrivateKey(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes == null");
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (InvalidKeySpecException e) {
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
