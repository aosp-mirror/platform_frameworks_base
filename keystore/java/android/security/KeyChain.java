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
import android.util.Log;
import dalvik.system.CloseGuard;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import org.apache.harmony.xnet.provider.jsse.IndexedPKIXParameters;
import org.apache.harmony.xnet.provider.jsse.SSLParametersImpl;

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
     * @hide Also used by KeyChainService implementation
     */
    // TODO This non-localized CA string to be removed when CAs moved out of keystore
    public static final String CA_SUFFIX = " CA";

    public static final String KEY_INTENT = "intent";

    /**
     * Intentionally not public to leave open the future possibility
     * of hardware based keys. Callers should use {@link #toPrivateKey
     * toPrivateKey} in order to convert a bundle to a {@code
     * PrivateKey}
     */
    private static final String KEY_PKCS8 = "pkcs8";

    /**
     * Intentionally not public to leave open the future possibility
     * of hardware based certs. Callers should use {@link
     * #toCertificate toCertificate} in order to convert a bundle to a
     * {@code PrivateKey}
     */
    private static final String KEY_X509 = "x509";

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
     * Returns a new {@code KeyChain} instance. When the caller is
     * done using the {@code KeyChain}, it must be closed with {@link
     * #close()} or resource leaks will occur.
     */
    public static KeyChain getInstance(Context context) throws InterruptedException {
        return new KeyChain(context);
    }

    private final AccountManager mAccountManager;

    private final Object mServiceLock = new Object();
    private IKeyChainService mService;
    private boolean mIsBound;

    private Account mAccount;

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mServiceLock) {
                mService = IKeyChainService.Stub.asInterface(service);
                mServiceLock.notifyAll();

                // Account is created if necessary during binding of the IKeyChainService
                mAccount = mAccountManager.getAccountsByType(ACCOUNT_TYPE)[0];
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (mServiceLock) {
                mService = null;
            }
        }
    };

    private final Context mContext;

    private final CloseGuard mGuard = CloseGuard.get();

    private KeyChain(Context context) throws InterruptedException {
        if (context == null) {
            throw new NullPointerException("context == null");
        }
        mContext = context;
        ensureNotOnMainThread();
        mAccountManager = AccountManager.get(mContext);
        mIsBound = mContext.bindService(new Intent(IKeyChainService.class.getName()),
                                        mServiceConnection,
                                        Context.BIND_AUTO_CREATE);
        if (!mIsBound) {
            throw new AssertionError();
        }
        synchronized (mServiceLock) {
            // there is a race between binding on this thread and the
            // callback on the main thread. wait until binding is done
            // to be sure we have the mAccount initialized.
            if (mService == null) {
                mServiceLock.wait();
            }
        }
        mGuard.open("close");
    }

    /**
     * {@code Bundle} will contain {@link #KEY_INTENT} if user needs
     * to confirm application access to requested key. In the alias
     * does not exist or there is an error, null is
     * returned. Otherwise the {@code Bundle} contains information
     * representing the private key which can be interpreted with
     * {@link #toPrivateKey toPrivateKey}.
     *
     * non-null alias
     */
    public Bundle getPrivate(String alias) {
        return get(alias, Credentials.USER_PRIVATE_KEY);
    }

    public Bundle getCertificate(String alias) {
        return get(alias, Credentials.USER_CERTIFICATE);
    }

    public Bundle getCaCertificate(String alias) {
        return get(alias, Credentials.CA_CERTIFICATE);
    }

    private Bundle get(String alias, String type) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }
        ensureNotOnMainThread();

        String authAlias = (type.equals(Credentials.CA_CERTIFICATE)) ? (alias + CA_SUFFIX) : alias;
        AccountManagerFuture<Bundle> future = mAccountManager.getAuthToken(mAccount,
                                                                           authAlias,
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
            result.putParcelable(KEY_INTENT, intent);
            return result;
        }
        String authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
        if (authToken == null) {
            throw new AssertionError("Invalid authtoken");
        }

        byte[] bytes;
        try {
            if (type.equals(Credentials.USER_PRIVATE_KEY)) {
                bytes = mService.getPrivate(alias, authToken);
            } else if (type.equals(Credentials.USER_CERTIFICATE)) {
                bytes = mService.getCertificate(alias, authToken);
            } else if (type.equals(Credentials.CA_CERTIFICATE)) {
                bytes = mService.getCaCertificate(alias, authToken);
            } else {
                throw new AssertionError();
            }
        } catch (RemoteException e) {
            throw new AssertionError(e);
        }
        if (bytes == null) {
            throw new AssertionError();
        }
        Bundle result = new Bundle();
        if (type.equals(Credentials.USER_PRIVATE_KEY)) {
            result.putByteArray(KEY_PKCS8, bytes);
        } else if (type.equals(Credentials.USER_CERTIFICATE)) {
            result.putByteArray(KEY_X509, bytes);
        } else if (type.equals(Credentials.CA_CERTIFICATE)) {
            result.putByteArray(KEY_X509, bytes);
        } else {
            throw new AssertionError();
        }
        return result;
    }

    public static PrivateKey toPrivateKey(Bundle bundle) {
        byte[] bytes = bundle.getByteArray(KEY_PKCS8);
        if (bytes == null) {
            throw new IllegalArgumentException("not a private key bundle");
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

    public static Bundle fromPrivateKey(PrivateKey privateKey) {
        Bundle bundle = new Bundle();
        String format = privateKey.getFormat();
        if (!format.equals("PKCS#8")) {
            throw new IllegalArgumentException("Unsupported private key format " + format);
        }
        bundle.putByteArray(KEY_PKCS8, privateKey.getEncoded());
        return bundle;
    }

    public static X509Certificate toCertificate(Bundle bundle) {
        byte[] bytes = bundle.getByteArray(KEY_X509);
        if (bytes == null) {
            throw new IllegalArgumentException("not a certificate bundle");
        }
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(bytes));
            return (X509Certificate) cert;
        } catch (CertificateException e) {
            throw new AssertionError(e);
        }
    }

    public static Bundle fromCertificate(Certificate cert) {
        Bundle bundle = new Bundle();
        String type = cert.getType();
        if (!type.equals("X.509")) {
            throw new IllegalArgumentException("Unsupported certificate type " + type);
        }
        try {
            bundle.putByteArray(KEY_X509, cert.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new AssertionError(e);
        }
        return bundle;
    }

    private void ensureNotOnMainThread() {
        Looper looper = Looper.myLooper();
        if (looper != null && looper == mContext.getMainLooper()) {
            throw new IllegalStateException(
                    "calling this from your main thread can lead to deadlock");
        }
    }

    public Bundle findIssuer(X509Certificate cert) {
        if (cert == null) {
            throw new NullPointerException("cert == null");
        }
        ensureNotOnMainThread();

        // check and see if the issuer is already known to the default IndexedPKIXParameters
        IndexedPKIXParameters index = SSLParametersImpl.getDefaultIndexedPKIXParameters();
        try {
            TrustAnchor anchor = index.findTrustAnchor(cert);
            if (anchor != null && anchor.getTrustedCert() != null) {
                X509Certificate ca = anchor.getTrustedCert();
                return fromCertificate(ca);
            }
        } catch (CertPathValidatorException ignored) {
        }

        // otherwise, it might be a user installed CA in the keystore
        String alias;
        try {
            alias = mService.findIssuer(fromCertificate(cert));
        } catch (RemoteException e) {
            throw new AssertionError(e);
        }
        if (alias == null) {
            Log.w(TAG, "Lookup failed for issuer");
            return null;
        }

        Bundle bundle = get(alias, Credentials.CA_CERTIFICATE);
        Intent intent = bundle.getParcelable(KEY_INTENT);
        if (intent != null) {
            // permission still required
            return bundle;
        }
        // add the found CA to the index for next time
        X509Certificate ca = toCertificate(bundle);
        index.index(new TrustAnchor(ca, null));
        return bundle;
    }

    public void close() {
        if (mIsBound) {
            mContext.unbindService(mServiceConnection);
            mIsBound = false;
            mGuard.close();
        }
    }

    protected void finalize() throws Throwable {
        // note we don't close, we just warn.
        // shouldn't be doing I/O in a finalizer,
        // which the unbind would cause.
        try {
            if (mGuard != null) {
                mGuard.warnIfOpen();
            }
        } finally {
            super.finalize();
        }
    }
}
