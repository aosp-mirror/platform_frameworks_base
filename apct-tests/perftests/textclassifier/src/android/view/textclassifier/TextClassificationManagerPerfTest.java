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
import android.perftests.utils.SettingsHelper;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class TextClassificationManagerPerfTest {

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @After
    public void tearDown() {
        SettingsHelper.delete(
                SettingsHelper.NAMESPACE_GLOBAL, Settings.Global.TEXT_CLASSIFIER_CONSTANTS);
    }

    @Test
    public void testGetTextClassifier_systemTextClassifierDisabled() {
        Context context = InstrumentationRegistry.getTargetContext();
        SettingsHelper.set(
                SettingsHelper.NAMESPACE_GLOBAL,
                Settings.Global.TEXT_CLASSIFIER_CONSTANTS,
                "system_textclassifier_enabled=false");
        TextClassificationManager textClassificationManager =
                context.getSystemService(TextClassificationManager.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            textClassificationManager.getTextClassifier();
            textClassificationManager.invalidateForTesting();
        }
    }

    @Test
    public void testGetTextClassifier_systemTextClassifierEnabled() {
        Context context = InstrumentationRegistry.getTargetContext();
        SettingsHelper.set(
                SettingsHelper.NAMESPACE_GLOBAL,
                Settings.Global.TEXT_CLASSIFIER_CONSTANTS,
                "system_textclassifier_enabled=true");
        TextClassificationManager textClassificationManager =
                context.getSystemService(TextClassificationManager.class);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            textClassificationManager.getTextClassifier();
            textClassificationManager.invalidateForTesting();
        }
    }
}
