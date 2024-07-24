/*
 * Copyright 2017 The Android Open Source Project
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

package android.conscrypt;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import javax.crypto.KeyGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Supported cipher transformations.
 */
@SuppressWarnings({"ImmutableEnumChecker", "unused"})
public enum Transformation {
    AES_CBC_PKCS5("AES", "CBC", "PKCS5Padding", new AesKeyGen()),
    AES_ECB_PKCS5("AES", "ECB", "PKCS5Padding", new AesKeyGen()),
    AES_GCM_NO("AES", "GCM", "NoPadding", new AesKeyGen()),
    RSA_ECB_PKCS1("RSA", "ECB", "PKCS1Padding", new RsaKeyGen());

    Transformation(String algorithm, String mode, String padding, KeyGen keyGen) {
        this.algorithm = algorithm;
        this.mode = mode;
        this.padding = padding;
        this.keyGen = keyGen;
    }

    final String algorithm;
    final String mode;
    final String padding;
    final KeyGen keyGen;

    String toFormattedString() {
        return algorithm + "/" + mode + "/" + padding;
    }

    Key newEncryptKey() {
        return keyGen.newEncryptKey();
    }

    private interface KeyGen { Key newEncryptKey(); }

    private static final class RsaKeyGen implements KeyGen {
        @Override
        public Key newEncryptKey() {
            try {
                // Use Bouncy castle
                KeyPairGenerator generator =
                        KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider());
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                return pair.getPublic();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class AesKeyGen implements KeyGen {
        @Override
        public Key newEncryptKey() {
            try {
                // Just use the JDK's provider.
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(256);
                return keyGen.generateKey();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }
}