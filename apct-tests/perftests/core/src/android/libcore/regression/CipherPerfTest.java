/*
 * Copyright (C) 2022 The Android Open Source Project.
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

import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Cipher benchmarks. Only runs on AES currently because of the combinatorial explosion of the test
 * as it stands.
 */
@RunWith(Parameterized.class)
@LargeTest
public class CipherPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameters(
            name =
                    "mMode({0}), mPadding({1}), mKeySize({2}), mInputSize({3}),"
                            + " mImplementation({4})")
    public static Collection cases() {
        int[] mKeySizes = new int[] {128, 192, 256};
        int[] inputSizes = new int[] {16, 32, 64, 128, 1024, 8192};
        final List<Object[]> params = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (Padding padding : Padding.values()) {
                for (Implementation implementation : Implementation.values()) {
                    if ((mode == Mode.CBC
                                    || mode == Mode.CFB
                                    || mode == Mode.CTR
                                    || mode == Mode.ECB
                                    || mode == Mode.OFB)
                            && padding == Padding.PKCS1PADDING) {
                        continue;
                    }
                    if ((mode == Mode.CFB || mode == Mode.OFB)
                            && padding == Padding.NOPADDING
                            && implementation == Implementation.OpenSSL) {
                        continue;
                    }
                    for (int mKeySize : mKeySizes) {
                        for (int inputSize : inputSizes) {
                            params.add(
                                    new Object[] {
                                        mode, padding, mKeySize, inputSize, implementation
                                    });
                        }
                    }
                }
            }
        }
        return params;
    }

    private static final int DATA_SIZE = 8192;
    private static final byte[] DATA = new byte[DATA_SIZE];

    private static final int IV_SIZE = 16;

    private static final byte[] IV = new byte[IV_SIZE];

    static {
        for (int i = 0; i < DATA_SIZE; i++) {
            DATA[i] = (byte) i;
        }
        for (int i = 0; i < IV_SIZE; i++) {
            IV[i] = (byte) i;
        }
    }

    public Algorithm mAlgorithm = Algorithm.AES;

    public enum Algorithm {
        AES,
    };

    @Parameterized.Parameter(0)
    public Mode mMode;

    public enum Mode {
        CBC,
        CFB,
        CTR,
        ECB,
        OFB,
    };

    @Parameterized.Parameter(1)
    public Padding mPadding;

    public enum Padding {
        NOPADDING,
        PKCS1PADDING,
    };

    @Parameterized.Parameter(2)
    public int mKeySize;

    @Parameterized.Parameter(3)
    public int mInputSize;

    @Parameterized.Parameter(4)
    public Implementation mImplementation;

    public enum Implementation {
        OpenSSL,
        BouncyCastle
    };

    private String mProviderName;

    // Key generation isn't part of the benchmark so cache the results
    private static Map<Integer, SecretKey> sKeySizes = new HashMap<Integer, SecretKey>();

    private String mCipherAlgorithm;
    private SecretKey mKey;

    private byte[] mOutput = new byte[DATA.length];

    private Cipher mCipherEncrypt;

    private Cipher mCipherDecrypt;

    private AlgorithmParameterSpec mSpec;

    @Before
    public void setUp() throws Exception {
        mCipherAlgorithm =
                mAlgorithm.toString() + "/" + mMode.toString() + "/" + mPadding.toString();

        String mKeyAlgorithm = mAlgorithm.toString();
        mKey = sKeySizes.get(mKeySize);
        if (mKey == null) {
            KeyGenerator generator = KeyGenerator.getInstance(mKeyAlgorithm);
            generator.init(mKeySize);
            mKey = generator.generateKey();
            sKeySizes.put(mKeySize, mKey);
        }

        switch (mImplementation) {
            case OpenSSL:
                mProviderName = "AndroidOpenSSL";
                break;
            case BouncyCastle:
                mProviderName = "BC";
                break;
            default:
                throw new RuntimeException(mImplementation.toString());
        }

        if (mMode != Mode.ECB) {
            mSpec = new IvParameterSpec(IV);
        }

        mCipherEncrypt = Cipher.getInstance(mCipherAlgorithm, mProviderName);
        mCipherEncrypt.init(Cipher.ENCRYPT_MODE, mKey, mSpec);

        mCipherDecrypt = Cipher.getInstance(mCipherAlgorithm, mProviderName);
        mCipherDecrypt.init(Cipher.DECRYPT_MODE, mKey, mSpec);
    }

    @Test
    public void timeEncrypt() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mCipherEncrypt.doFinal(DATA, 0, mInputSize, mOutput);
        }
    }

    @Test
    public void timeDecrypt() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mCipherDecrypt.doFinal(DATA, 0, mInputSize, mOutput);
        }
    }
}
