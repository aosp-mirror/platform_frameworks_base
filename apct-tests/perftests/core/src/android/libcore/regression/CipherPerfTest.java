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

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

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
@RunWith(JUnitParamsRunner.class)
@LargeTest
public class CipherPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection getCases() {
        int[] keySizes = new int[] {128, 192, 256};
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
                    for (int keySize : keySizes) {
                        for (int inputSize : inputSizes) {
                            params.add(
                                    new Object[] {
                                        mode, padding, keySize, inputSize, implementation
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

    public enum Mode {
        CBC,
        CFB,
        CTR,
        ECB,
        OFB,
    };

    public enum Padding {
        NOPADDING,
        PKCS1PADDING,
    };

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

    public void setUp(Mode mode, Padding padding, int keySize, Implementation implementation)
            throws Exception {
        mCipherAlgorithm = mAlgorithm.toString() + "/" + mode.toString() + "/" + padding.toString();

        String mKeyAlgorithm = mAlgorithm.toString();
        mKey = sKeySizes.get(keySize);
        if (mKey == null) {
            KeyGenerator generator = KeyGenerator.getInstance(mKeyAlgorithm);
            generator.init(keySize);
            mKey = generator.generateKey();
            sKeySizes.put(keySize, mKey);
        }

        switch (implementation) {
            case OpenSSL:
                mProviderName = "AndroidOpenSSL";
                break;
            case BouncyCastle:
                mProviderName = "BC";
                break;
            default:
                throw new RuntimeException(implementation.toString());
        }

        if (mode != Mode.ECB) {
            mSpec = new IvParameterSpec(IV);
        }

        mCipherEncrypt = Cipher.getInstance(mCipherAlgorithm, mProviderName);
        mCipherEncrypt.init(Cipher.ENCRYPT_MODE, mKey, mSpec);

        mCipherDecrypt = Cipher.getInstance(mCipherAlgorithm, mProviderName);
        mCipherDecrypt.init(Cipher.DECRYPT_MODE, mKey, mSpec);
    }

    @Test
    @Parameters(method = "getCases")
    public void timeEncrypt(
            Mode mode, Padding padding, int keySize, int inputSize, Implementation implementation)
            throws Exception {
        setUp(mode, padding, keySize, implementation);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mCipherEncrypt.doFinal(DATA, 0, inputSize, mOutput);
        }
    }

    @Test
    @Parameters(method = "getCases")
    public void timeDecrypt(
            Mode mode, Padding padding, int keySize, int inputSize, Implementation implementation)
            throws Exception {
        setUp(mode, padding, keySize, implementation);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mCipherDecrypt.doFinal(DATA, 0, inputSize, mOutput);
        }
    }
}
