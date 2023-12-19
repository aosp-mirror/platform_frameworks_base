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

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class KeyPairGeneratorPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {Algorithm.RSA, Implementation.BouncyCastle},
                    {Algorithm.DSA, Implementation.BouncyCastle},
                    {Algorithm.RSA, Implementation.OpenSSL}
                });
    }

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

    public void setUp(Algorithm algorithm, Implementation implementation) throws Exception {
        this.mGeneratorAlgorithm = algorithm.toString();

        final String provider;
        if (implementation == Implementation.BouncyCastle) {
            provider = "BC";
        } else {
            provider = "AndroidOpenSSL";
        }

        this.mGenerator = KeyPairGenerator.getInstance(mGeneratorAlgorithm, provider);
        this.mGenerator.initialize(1024);
    }

    @Test
    @Parameters(method = "getData")
    public void time(Algorithm algorithm, Implementation implementation) throws Exception {
        setUp(algorithm, implementation);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            KeyPair keyPair = mGenerator.generateKeyPair();
        }
    }
}
