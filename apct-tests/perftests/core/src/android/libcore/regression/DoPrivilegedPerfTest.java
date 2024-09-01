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

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.AccessController;
import java.security.PrivilegedAction;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class DoPrivilegedPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void timeDirect() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String lineSeparator = System.getProperty("line.separator");
        }
    }

    @Test
    public void timeFastAndSlow() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String lineSeparator;
            if (System.getSecurityManager() == null) {
                lineSeparator = System.getProperty("line.separator");
            } else {
                lineSeparator = AccessController.doPrivileged(new PrivilegedAction<String>() {
                    public String run() {
                        return System.getProperty("line.separator");
                    }
                });
            }
        }
    }

    @Test
    public void timeNewAction() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String lineSeparator = AccessController.doPrivileged(new PrivilegedAction<String>() {
                public String run() {
                    return System.getProperty("line.separator");
                }
            });
        }
    }

    @Test
    public void timeReusedAction() throws Exception {
        final PrivilegedAction<String> action = new ReusableAction("line.separator");
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            String lineSeparator = AccessController.doPrivileged(action);
        }
    }

    private static final class ReusableAction implements PrivilegedAction<String> {
        private final String mPropertyName;

        ReusableAction(String propertyName) {
            this.mPropertyName = propertyName;
        }

        public String run() {
            return System.getProperty(mPropertyName);
        }
    }
}
