package com.android.server.accounts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * A crypto helper for encrypting and decrypting bundle with in-memory symmetric
 * key for {@link AccountManagerService}.
 */
/* default */ class CryptoHelper {
    private static final String TAG = "Account";

    private static final String KEY_CIPHER = "cipher";
    private static final String KEY_MAC = "mac";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KEY_IV = "iv";
    private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String MAC_ALGORITHM = "HMACSHA256";
    private static final int IV_LENGTH = 16;

    private static CryptoHelper sInstance;
    // Keys used for encrypting and decrypting data returned in a Bundle.
    private final SecretKey mEncryptionKey;
    private final SecretKey mMacKey;

    /* default */ synchronized static CryptoHelper getInstance() throws NoSuchAlgorithmException {
        if (sInstance == null) {
            sInstance = new CryptoHelper();
        }
        return sInstance;
    }

    private CryptoHelper() throws NoSuchAlgorithmException {
        KeyGenerator kgen = KeyGenerator.getInstance(KEY_ALGORITHM);
        mEncryptionKey = kgen.generateKey();
        // Use a different key for mac-ing than encryption/decryption.
        kgen = KeyGenerator.getInstance(MAC_ALGORITHM);
        mMacKey = kgen.generateKey();
    }

    @NonNull
    /* default */ Bundle encryptBundle(@NonNull Bundle bundle) throws GeneralSecurityException {
        Preconditions.checkNotNull(bundle, "Cannot encrypt null bundle.");
        Parcel parcel = Parcel.obtain();
        bundle.writeToParcel(parcel, 0);
        byte[] clearBytes = parcel.marshall();
        parcel.recycle();

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, mEncryptionKey);
        byte[] encryptedBytes = cipher.doFinal(clearBytes);
        byte[] iv = cipher.getIV();
        byte[] mac = createMac(encryptedBytes, iv);

        Bundle encryptedBundle = new Bundle();
        encryptedBundle.putByteArray(KEY_CIPHER, encryptedBytes);
        encryptedBundle.putByteArray(KEY_MAC, mac);
        encryptedBundle.putByteArray(KEY_IV, iv);

        return encryptedBundle;
    }

    @Nullable
    /* default */ Bundle decryptBundle(@NonNull Bundle bundle) throws GeneralSecurityException {
        Preconditions.checkNotNull(bundle, "Cannot decrypt null bundle.");
        byte[] iv = bundle.getByteArray(KEY_IV);
        byte[] encryptedBytes = bundle.getByteArray(KEY_CIPHER);
        byte[] mac = bundle.getByteArray(KEY_MAC);
        if (!verifyMac(encryptedBytes, iv, mac)) {
            Log.w(TAG, "Escrow mac mismatched!");
            return null;
        }

        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, mEncryptionKey, ivSpec);
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

        Parcel decryptedParcel = Parcel.obtain();
        decryptedParcel.unmarshall(decryptedBytes, 0, decryptedBytes.length);
        decryptedParcel.setDataPosition(0);
        Bundle decryptedBundle = new Bundle();
        decryptedBundle.readFromParcel(decryptedParcel);
        decryptedParcel.recycle();
        return decryptedBundle;
    }

    private boolean verifyMac(@Nullable byte[] cipherArray, @Nullable byte[] iv, @Nullable byte[] macArray)
            throws GeneralSecurityException {
        if (cipherArray == null || cipherArray.length == 0 || macArray == null
                || macArray.length == 0) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Cipher or MAC is empty!");
            }
            return false;
        }
        return constantTimeArrayEquals(macArray, createMac(cipherArray, iv));
    }

    @NonNull
    private byte[] createMac(@NonNull byte[] cipher, @NonNull byte[] iv) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        mac.init(mMacKey);
        mac.update(cipher);
        mac.update(iv);
        return mac.doFinal();
    }

    private static boolean constantTimeArrayEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        boolean isEqual = true;
        for (int i = 0; i < b.length; i++) {
            isEqual &= (a[i] == b[i]);
        }
        return isEqual;
    }
}
