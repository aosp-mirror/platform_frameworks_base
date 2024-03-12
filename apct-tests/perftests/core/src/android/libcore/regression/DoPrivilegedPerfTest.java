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

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.AccessController;
import java.security.PrivilegedAction;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class DoPrivilegedPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeDirect() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String lineSeparator = System.getProperty("line.separator");
        }
    }

    @Test
    public void timeFastAndSlow() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
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
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
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
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
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
