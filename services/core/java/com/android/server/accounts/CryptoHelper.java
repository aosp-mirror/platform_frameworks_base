package com.android.server.accounts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A crypto helper for encrypting and decrypting bundle with in-memory symmetric
 * key for {@link AccountManagerService}.
 */
/* default */ class CryptoHelper {
    private static final String TAG = "Account";

    private static final String KEY_CIPHER = "cipher";
    private static final String KEY_MAC = "mac";
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String MAC_ALGORITHM = "HMACSHA256";
    private static final int IV_LENGTH = 16;

    private static CryptoHelper sInstance;
    // Keys used for encrypting and decrypting data returned in a Bundle.
    private final SecretKeySpec mCipherKeySpec;
    private final SecretKeySpec mMacKeySpec;
    private final IvParameterSpec mIv;

    /* default */ synchronized static CryptoHelper getInstance() throws NoSuchAlgorithmException {
        if (sInstance == null) {
            sInstance = new CryptoHelper();
        }
        return sInstance;
    }

    private CryptoHelper() throws NoSuchAlgorithmException {
        KeyGenerator kgen = KeyGenerator.getInstance(KEY_ALGORITHM);
        SecretKey skey = kgen.generateKey();
        mCipherKeySpec = new SecretKeySpec(skey.getEncoded(), KEY_ALGORITHM);

        kgen = KeyGenerator.getInstance(MAC_ALGORITHM);
        skey = kgen.generateKey();
        mMacKeySpec = new SecretKeySpec(skey.getEncoded(), MAC_ALGORITHM);

        // Create random iv
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(iv);
        mIv = new IvParameterSpec(iv);
    }

    @NonNull
    /* default */ Bundle encryptBundle(@NonNull Bundle bundle) throws GeneralSecurityException {
        Preconditions.checkNotNull(bundle, "Cannot encrypt null bundle.");
        Parcel parcel = Parcel.obtain();
        bundle.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        Bundle encryptedBundle = new Bundle();

        byte[] cipher = encrypt(bytes);
        byte[] mac = createMac(cipher);

        encryptedBundle.putByteArray(KEY_CIPHER, cipher);
        encryptedBundle.putByteArray(KEY_MAC, mac);

        return encryptedBundle;
    }

    @Nullable
    /* default */ Bundle decryptBundle(@NonNull Bundle bundle) throws GeneralSecurityException {
        Preconditions.checkNotNull(bundle, "Cannot decrypt null bundle.");
        byte[] cipherArray = bundle.getByteArray(KEY_CIPHER);
        byte[] macArray = bundle.getByteArray(KEY_MAC);

        if (!verifyMac(cipherArray, macArray)) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Escrow mac mismatched!");
            }
            return null;
        }

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, mCipherKeySpec, mIv);
        byte[] decryptedBytes = cipher.doFinal(cipherArray);

        Parcel decryptedParcel = Parcel.obtain();
        decryptedParcel.unmarshall(decryptedBytes, 0, decryptedBytes.length);
        decryptedParcel.setDataPosition(0);
        Bundle decryptedBundle = new Bundle();
        decryptedBundle.readFromParcel(decryptedParcel);
        decryptedParcel.recycle();
        return decryptedBundle;
    }

    private boolean verifyMac(@Nullable byte[] cipherArray, @Nullable byte[] macArray)
            throws GeneralSecurityException {

        if (cipherArray == null || cipherArray.length == 0 || macArray == null
                || macArray.length == 0) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Cipher or MAC is empty!");
            }
            return false;
        }
        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        mac.init(mMacKeySpec);
        mac.update(cipherArray);
        return Arrays.equals(macArray, mac.doFinal());
    }

    @NonNull
    private byte[] encrypt(@NonNull byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, mCipherKeySpec, mIv);
        return cipher.doFinal(data);
    }

    @NonNull
    private byte[] createMac(@NonNull byte[] cipher) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        mac.init(mMacKeySpec);
        return mac.doFinal(cipher);
    }
}
