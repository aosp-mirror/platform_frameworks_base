/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keystore;

import android.annotation.NonNull;
import android.security.KeyStore;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

import java.security.InvalidKeyException;
import java.security.SignatureSpi;

/**
 * Base class for {@link SignatureSpi} providing Android KeyStore backed ECDSA signatures.
 *
 * @hide
 */
abstract class AndroidKeyStoreECDSASignatureSpi extends AndroidKeyStoreSignatureSpiBase {

    public final static class NONE extends AndroidKeyStoreECDSASignatureSpi {
        public NONE() {
            super(KeymasterDefs.KM_DIGEST_NONE);
        }
    }

    public final static class SHA1 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA1() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
    }

    public final static class SHA224 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA224() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
    }

    public final static class SHA256 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA256() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
    }

    public final static class SHA384 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA384() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
    }

    public final static class SHA512 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA512() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
    }

    private final int mKeymasterDigest;

    private int mGroupSizeBytes = -1;

    AndroidKeyStoreECDSASignatureSpi(int keymasterDigest) {
        mKeymasterDigest = keymasterDigest;
    }

    @Override
    protected final void initKey(AndroidKeyStoreKey key) throws InvalidKeyException {
        if (!KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(key.getAlgorithm())) {
            throw new InvalidKeyException("Unsupported key algorithm: " + key.getAlgorithm()
                    + ". Only" + KeyProperties.KEY_ALGORITHM_EC + " supported");
        }

        KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
        int errorCode = getKeyStore().getKeyCharacteristics(
                key.getAlias(), null, null, keyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            throw getKeyStore().getInvalidKeyException(key.getAlias(), errorCode);
        }
        long keySizeBits = keyCharacteristics.getUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, -1);
        if (keySizeBits == -1) {
            throw new InvalidKeyException("Size of key not known");
        } else if (keySizeBits > Integer.MAX_VALUE) {
            throw new InvalidKeyException("Key too large: " + keySizeBits + " bits");
        }
        mGroupSizeBytes = (int) ((keySizeBits + 7) / 8);

        super.initKey(key);
    }

    @Override
    protected final void resetAll() {
        mGroupSizeBytes = -1;
        super.resetAll();
    }

    @Override
    protected final void resetWhilePreservingInitState() {
        super.resetWhilePreservingInitState();
    }

    @Override
    protected void addAlgorithmSpecificParametersToBegin(
            @NonNull KeymasterArguments keymasterArgs) {
        keymasterArgs.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_EC);
        keymasterArgs.addEnum(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest);
    }

    @Override
    protected int getAdditionalEntropyAmountForSign() {
        return mGroupSizeBytes;
    }
}
