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
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import java.io.ByteArrayInputStream;
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
     * Returns an {@code Intent} for use with {@link
     * android.app.Activity#startActivityForResult
     * startActivityForResult}. The result will be returned via {@link
     * android.app.Activity#onActivityResult onActivityResult} with
     * {@link android.app.Activity#RESULT_OK RESULT_OK} and the alias
     * in the returned {@code Intent}'s extra data with key {@link
     * android.content.Intent#EXTRA_TEXT Intent.EXTRA_TEXT}.
     */
    public static Intent chooseAlias() {
        return new Intent("com.android.keychain.CHOOSER");
    }

    /**
     * Returns a new {@code KeyChainResult} instance.
     */
    public static KeyChainResult get(Context context, String alias)
            throws InterruptedException, RemoteException {
        if (context == null) {
            throw new NullPointerException("context == null");
        }
        if (alias == null) {
            throw new NullPointerException("alias == null");
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
        IKeyChainService keyChainService;
        try {
            keyChainService = q.take();

            // Account is created if necessary during binding of the IKeyChainService
            AccountManager accountManager = AccountManager.get(context);
            Account account = accountManager.getAccountsByType(ACCOUNT_TYPE)[0];
            AccountManagerFuture<Bundle> future = accountManager.getAuthToken(account,
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
                throw new AssertionError(e);
            } catch (AuthenticatorException e) {
                throw new AssertionError(e);
            }
            Intent intent = bundle.getParcelable(AccountManager.KEY_INTENT);
            if (intent != null) {
                Bundle result = new Bundle();
                // we don't want this Eclair compatability flag,
                // it will prevent onActivityResult from being called
                intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
                return new KeyChainResult(intent);
            }

            String authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
            if (authToken == null) {
                throw new AssertionError("Invalid authtoken");
            }
            byte[] privateKeyBytes = keyChainService.getPrivateKey(alias, authToken);
            byte[] certificateBytes = keyChainService.getCertificate(alias, authToken);
            return new KeyChainResult(toPrivateKey(privateKeyBytes),
                                      toCertificate(certificateBytes));
        } finally {
            context.unbindService(keyChainServiceConnection);
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

    private static void ensureNotOnMainThread(Context context) {
        Looper looper = Looper.myLooper();
        if (looper != null && looper == context.getMainLooper()) {
            throw new IllegalStateException(
                    "calling this from your main thread can lead to deadlock");
        }
    }
}
