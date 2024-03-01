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

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/** CipherInputStream benchmark. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class CipherInputStreamPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final int DATA_SIZE = 1024 * 1024;
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

    private SecretKey mKey;

    private byte[] mOutput = new byte[8192];

    private Cipher mCipherEncrypt;

    private AlgorithmParameterSpec mSpec;

    @Before
    public void setUp() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        mKey = generator.generateKey();

        mSpec = new IvParameterSpec(IV);

        mCipherEncrypt = Cipher.getInstance("AES/CBC/PKCS5Padding");
        mCipherEncrypt.init(Cipher.ENCRYPT_MODE, mKey, mSpec);
    }

    @Test
    public void timeEncrypt() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mCipherEncrypt.init(Cipher.ENCRYPT_MODE, mKey, mSpec);
            InputStream is = new CipherInputStream(new ByteArrayInputStream(DATA), mCipherEncrypt);
            while (is.read(mOutput) != -1) {
                // Keep iterating
            }
        }
    }
}
