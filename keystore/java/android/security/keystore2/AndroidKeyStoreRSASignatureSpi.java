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

package android.security.keystore2;

import android.annotation.NonNull;
import android.hardware.security.keymint.KeyParameter;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyProperties;

import java.security.InvalidKeyException;
import java.security.SignatureSpi;
import java.util.List;

/**
 * Base class for {@link SignatureSpi} providing Android KeyStore backed RSA signatures.
 *
 * @hide
 */
abstract class AndroidKeyStoreRSASignatureSpi extends AndroidKeyStoreSignatureSpiBase {

    abstract static class PKCS1Padding extends AndroidKeyStoreRSASignatureSpi {
        PKCS1Padding(int keymasterDigest) {
            super(keymasterDigest, KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN);
        }

        @Override
        protected final int getAdditionalEntropyAmountForSign() {
            // No entropy required for this deterministic signature scheme.
            return 0;
        }
    }

    public static final class NONEWithPKCS1Padding extends PKCS1Padding {
        public NONEWithPKCS1Padding() {
            super(KeymasterDefs.KM_DIGEST_NONE);
        }
        @Override
        protected String getAlgorithm() {
            return "NONEwithRSA";
        }
    }

    public static final class MD5WithPKCS1Padding extends PKCS1Padding {
        public MD5WithPKCS1Padding() {
            super(KeymasterDefs.KM_DIGEST_MD5);
        }
        @Override
        protected String getAlgorithm() {
            return "MD5withRSA";
        }
    }

    public static final class SHA1WithPKCS1Padding extends PKCS1Padding {
        public SHA1WithPKCS1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA1withRSA";
        }
    }

    public static final class SHA224WithPKCS1Padding extends PKCS1Padding {
        public SHA224WithPKCS1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA224withRSA";
        }
    }

    public static final class SHA256WithPKCS1Padding extends PKCS1Padding {
        public SHA256WithPKCS1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA256withRSA";
        }
    }

    public static final class SHA384WithPKCS1Padding extends PKCS1Padding {
        public SHA384WithPKCS1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA384withRSA";
        }
    }

    public static final class SHA512WithPKCS1Padding extends PKCS1Padding {
        public SHA512WithPKCS1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA512withRSA";
        }
    }

    abstract static class PSSPadding extends AndroidKeyStoreRSASignatureSpi {
        private static final int SALT_LENGTH_BYTES = 20;

        PSSPadding(int keymasterDigest) {
            super(keymasterDigest, KeymasterDefs.KM_PAD_RSA_PSS);
        }

        @Override
        protected final int getAdditionalEntropyAmountForSign() {
            return SALT_LENGTH_BYTES;
        }
    }

    public static final class SHA1WithPSSPadding extends PSSPadding {
        public SHA1WithPSSPadding() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA1withRSA/PSS";
        }
    }

    public static final class SHA224WithPSSPadding extends PSSPadding {
        public SHA224WithPSSPadding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA224withRSA/PSS";
        }
    }

    public static final class SHA256WithPSSPadding extends PSSPadding {
        public SHA256WithPSSPadding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA256withRSA/PSS";
        }
    }

    public static final class SHA384WithPSSPadding extends PSSPadding {
        public SHA384WithPSSPadding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA384withRSA/PSS";
        }
    }

    public static final class SHA512WithPSSPadding extends PSSPadding {
        public SHA512WithPSSPadding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA512withRSA/PSS";
        }
    }

    private final int mKeymasterDigest;
    private final int mKeymasterPadding;

    AndroidKeyStoreRSASignatureSpi(int keymasterDigest, int keymasterPadding) {
        mKeymasterDigest = keymasterDigest;
        mKeymasterPadding = keymasterPadding;
    }

    @Override
    protected final void initKey(AndroidKeyStoreKey key) throws InvalidKeyException {
        if (!KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(key.getAlgorithm())) {
            throw new InvalidKeyException("Unsupported key algorithm: " + key.getAlgorithm()
                    + ". Only" + KeyProperties.KEY_ALGORITHM_RSA + " supported");
        }
        super.initKey(key);
    }

    @Override
    protected final void resetAll() {
        super.resetAll();
    }

    @Override
    protected final void resetWhilePreservingInitState() {
        super.resetWhilePreservingInitState();
    }

    @Override
    protected final void addAlgorithmSpecificParametersToBegin(
            @NonNull List<KeyParameter> parameters) {
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_RSA
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_PADDING, mKeymasterPadding
        ));
    }
}
