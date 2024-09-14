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
package com.android.ravenwoodtest.bivalenttest;

import static android.platform.test.ravenwood.RavenwoodConfig.isOnRavenwood;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.platform.test.ravenwood.RavenwoodConfig;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test to make sure the config field is used.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodConfigTest {
    private static final String PACKAGE_NAME = "com.test";

    @RavenwoodConfig.Config
    public static RavenwoodConfig sConfig =
            new RavenwoodConfig.Builder()
                    .setPackageName(PACKAGE_NAME)
                    .build();

    @Test
    public void testConfig() {
        assumeTrue(isOnRavenwood());
        assertEquals(PACKAGE_NAME,
                InstrumentationRegistry.getInstrumentation().getContext().getPackageName());
    }
}
