/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwoodtest.bivalentinst;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.platform.test.ravenwood.RavenwoodConfig;
import android.platform.test.ravenwood.RavenwoodConfig.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the case where the instrumentation target is the test APK itself.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodInstrumentationTest_self {

    private static final String TARGET_PACKAGE_NAME =
            "com.android.ravenwood.bivalentinsttest_self_inst";
    private static final String TEST_PACKAGE_NAME =
            "com.android.ravenwood.bivalentinsttest_self_inst";

    @Config
    public static final RavenwoodConfig sConfig = new RavenwoodConfig.Builder()
            .setPackageName(TEST_PACKAGE_NAME)
            .setTargetPackageName(TARGET_PACKAGE_NAME)
            .build();


    private static Instrumentation sInstrumentation;
    private static Context sTestContext;
    private static Context sTargetContext;

    @BeforeClass
    public static void beforeClass() {
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
        sTestContext = sInstrumentation.getContext();
        sTargetContext = sInstrumentation.getTargetContext();
    }

    @Test
    public void testTestContextPackageName() {
        assertThat(sTestContext.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
    }

    @Test
    public void testTargetContextPackageName() {
        assertThat(sTargetContext.getPackageName()).isEqualTo(TARGET_PACKAGE_NAME);
    }

    @Test
    public void testTestAppContextPackageName() {
        assertThat(sTestContext.getApplicationContext().getPackageName())
                .isEqualTo(TEST_PACKAGE_NAME);
    }

    @Test
    public void testTestAppAppContextPackageName() {
        assertThat(sTestContext.getApplicationContext().getPackageName())
                .isEqualTo(TEST_PACKAGE_NAME);
    }

    @Test
    public void testTargetAppContextPackageName() {
        assertThat(sTargetContext.getApplicationContext()
                .getApplicationContext().getPackageName())
                .isEqualTo(TARGET_PACKAGE_NAME);
    }

    @Test
    public void testTargetAppAppContextPackageName() {
        assertThat(sTargetContext.getApplicationContext()
                .getApplicationContext().getPackageName())
                .isEqualTo(TARGET_PACKAGE_NAME);
    }

    @Test
    public void testContextSameness() {
        assertThat(sTargetContext).isNotSameInstanceAs(sTestContext);

        assertThat(sTestContext).isNotSameInstanceAs(sTestContext.getApplicationContext());
        assertThat(sTargetContext).isNotSameInstanceAs(sTargetContext.getApplicationContext());

        assertThat(sTestContext.getApplicationContext()).isSameInstanceAs(
                sTestContext.getApplicationContext().getApplicationContext());
        assertThat(sTargetContext.getApplicationContext()).isSameInstanceAs(
                sTargetContext.getApplicationContext().getApplicationContext());
    }

    @Test
    public void testTargetAppResource() {
        assertThat(sTargetContext.getString(
                com.android.ravenwood.bivalentinsttest_self_inst.R.string.test_string_in_test))
                .isEqualTo("String in test APK");
    }

    @Test
    public void testTestAppResource() {
        assertThat(sTestContext.getString(
                com.android.ravenwood.bivalentinsttest_self_inst.R.string.test_string_in_test))
                .isEqualTo("String in test APK");
    }
}
