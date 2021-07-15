/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.inprocesstests;

import static com.google.common.truth.Truth.assertThat;

import android.os.Process;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileReader;

public class InstrumentSystemServerTest {

    private static final String TAG = "InstrumentSystemServerTest";

    @Test
    public void testCodeIsRunningInSystemServer() throws Exception {
        assertThat(InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName())
                .isEqualTo("android");
        assertThat(Process.myUid()).isEqualTo(Process.SYSTEM_UID);
        assertThat(readCmdLine()).isEqualTo("system_server");
    }

    private static String readCmdLine() throws Exception {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader("/proc/self/cmdline"));
            return in.readLine().trim();
        } finally {
            in.close();
        }
    }

}
