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
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class KeyPairGeneratorPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "mAlgorithm={0}, mImplementation={1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[][] {
                    {Algorithm.RSA, Implementation.BouncyCastle},
                    {Algorithm.DSA, Implementation.BouncyCastle},
                    {Algorithm.RSA, Implementation.OpenSSL}
                });
    }

    @Parameterized.Parameter(0)
    public Algorithm mAlgorithm;

    @Parameterized.Parameter(1)
    public Implementation mImplementation;

    public enum Algorithm {
        RSA,
        DSA,
    };

    public enum Implementation {
        OpenSSL,
        BouncyCastle
    };

    private String mGeneratorAlgorithm;
    private KeyPairGenerator mGenerator;
    private SecureRandom mRandom;

    @Before
    public void setUp() throws Exception {
        this.mGeneratorAlgorithm = mAlgorithm.toString();

        final String provider;
        if (mImplementation == Implementation.BouncyCastle) {
            provider = "BC";
        } else {
            provider = "AndroidOpenSSL";
        }

        this.mGenerator = KeyPairGenerator.getInstance(mGeneratorAlgorithm, provider);
        this.mRandom = SecureRandom.getInstance("SHA1PRNG");
        this.mGenerator.initialize(1024);
    }

    @Test
    public void time() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            KeyPair keyPair = mGenerator.generateKeyPair();
        }
    }
}
