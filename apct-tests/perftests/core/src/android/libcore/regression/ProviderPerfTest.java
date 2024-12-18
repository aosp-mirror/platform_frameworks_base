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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.Provider;
import java.security.Security;

import javax.crypto.Cipher;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ProviderPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeStableProviders() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Cipher c = Cipher.getInstance("RSA");
        }
    }

    @Test
    public void timeWithNewProvider() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Security.addProvider(new MockProvider());
            try {
                Cipher c = Cipher.getInstance("RSA");
            } finally {
                Security.removeProvider("Mock");
            }
        }
    }

    private static class MockProvider extends Provider {
        MockProvider() {
            super("Mock", 1.0, "Mock me!");
        }
    }
}
