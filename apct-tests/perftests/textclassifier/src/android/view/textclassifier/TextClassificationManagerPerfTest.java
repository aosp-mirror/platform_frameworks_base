/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.textclassifier;

import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class TextClassificationManagerPerfTest {
    private static final String WRITE_DEVICE_CONFIG_PERMISSION =
            "android.permission.WRITE_DEVICE_CONFIG";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private String mOriginalSystemTextclassifierStatus;

    @BeforeClass
    public static void setUpClass() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(
                        WRITE_DEVICE_CONFIG_PERMISSION);
    }

    @AfterClass
    public static void tearDownClass() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Before
    public void setUp() {
        // Saves config original value.
        mOriginalSystemTextclassifierStatus = DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER, "system_textclassifier_enabled");
    }

    @After
    public void tearDown() {
        // Restores config original value.
        enableSystemTextclassifier(mOriginalSystemTextclassifierStatus);
    }

    @Test
    public void testGetTextClassifier_systemTextClassifierDisabled() {
        Context context = InstrumentationRegistry.getTargetContext();
        enableSystemTextclassifier(String.valueOf(false));
        TextClassificationManager textClassificationManager =
                context.getSystemService(TextClassificationManager.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            textClassificationManager.getTextClassifier();
        }
    }

    @Test
    public void testGetTextClassifier_systemTextClassifierEnabled() {
        Context context = InstrumentationRegistry.getTargetContext();
        enableSystemTextclassifier(String.valueOf(true));
        TextClassificationManager textClassificationManager =
                context.getSystemService(TextClassificationManager.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            textClassificationManager.getTextClassifier();
        }
    }

    private void enableSystemTextclassifier(String enabled) {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                "system_textclassifier_enabled", enabled, /* makeDefault */ false);
    }
}
