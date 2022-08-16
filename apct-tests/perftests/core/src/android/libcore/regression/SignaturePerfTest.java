/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Tests RSA and DSA mSignature creation and verification. */
@RunWith(Parameterized.class)
@LargeTest
public class SignaturePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "mAlgorithm={0}, mImplementation={1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[][] {
                    {Algorithm.MD5WithRSA, Implementation.OpenSSL},
                    {Algorithm.SHA1WithRSA, Implementation.OpenSSL},
                    {Algorithm.SHA256WithRSA, Implementation.OpenSSL},
                    {Algorithm.SHA384WithRSA, Implementation.OpenSSL},
                    {Algorithm.SHA512WithRSA, Implementation.OpenSSL},
                    {Algorithm.SHA1withDSA, Implementation.BouncyCastle}
                });
    }

    @Parameterized.Parameter(0)
    public Algorithm mAlgorithm;

    @Parameterized.Parameter(1)
    public Implementation mImplementation;

    private static final int DATA_SIZE = 8192;
    private static final byte[] DATA = new byte[DATA_SIZE];

    static {
        for (int i = 0; i < DATA_SIZE; i++) {
            DATA[i] = (byte) i;
        }
    }

    public enum Algorithm {
        MD5WithRSA,
        SHA1WithRSA,
        SHA256WithRSA,
        SHA384WithRSA,
        SHA512WithRSA,
        SHA1withDSA
    };

    public enum Implementation {
        OpenSSL,
        BouncyCastle
    };

    // Key generation and signing aren't part of the benchmark for verification
    // so cache the results
    private static Map<String, KeyPair> sKeyPairs = new HashMap<String, KeyPair>();
    private static Map<String, byte[]> sSignatures = new HashMap<String, byte[]>();

    private String mSignatureAlgorithm;
    private byte[] mSignature;
    private PrivateKey mPrivateKey;
    private PublicKey mPublicKey;

    @Before
    public void setUp() throws Exception {
        this.mSignatureAlgorithm = mAlgorithm.toString();

        String keyAlgorithm =
                mSignatureAlgorithm.substring(
                        mSignatureAlgorithm.length() - 3, mSignatureAlgorithm.length());
        KeyPair keyPair = sKeyPairs.get(keyAlgorithm);
        if (keyPair == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(keyAlgorithm);
            keyPair = generator.generateKeyPair();
            sKeyPairs.put(keyAlgorithm, keyPair);
        }
        this.mPrivateKey = keyPair.getPrivate();
        this.mPublicKey = keyPair.getPublic();

        this.mSignature = sSignatures.get(mSignatureAlgorithm);
        if (this.mSignature == null) {
            Signature signer = Signature.getInstance(mSignatureAlgorithm);
            signer.initSign(keyPair.getPrivate());
            signer.update(DATA);
            this.mSignature = signer.sign();
            sSignatures.put(mSignatureAlgorithm, mSignature);
        }
    }

    @Test
    public void timeSign() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Signature signer;
            switch (mImplementation) {
                case OpenSSL:
                    signer = Signature.getInstance(mSignatureAlgorithm, "AndroidOpenSSL");
                    break;
                case BouncyCastle:
                    signer = Signature.getInstance(mSignatureAlgorithm, "BC");
                    break;
                default:
                    throw new RuntimeException(mImplementation.toString());
            }
            signer.initSign(mPrivateKey);
            signer.update(DATA);
            signer.sign();
        }
    }

    @Test
    public void timeVerify() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Signature verifier;
            switch (mImplementation) {
                case OpenSSL:
                    verifier = Signature.getInstance(mSignatureAlgorithm, "AndroidOpenSSL");
                    break;
                case BouncyCastle:
                    verifier = Signature.getInstance(mSignatureAlgorithm, "BC");
                    break;
                default:
                    throw new RuntimeException(mImplementation.toString());
            }
            verifier.initVerify(mPublicKey);
            verifier.update(DATA);
            verifier.verify(mSignature);
        }
    }
}
