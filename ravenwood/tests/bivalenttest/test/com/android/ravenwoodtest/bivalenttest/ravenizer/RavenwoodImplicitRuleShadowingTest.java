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
package com.android.ravenwoodtest.bivalenttest.ravenizer;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test to make sure when a test class inherits another test class, the base class's
 * implicit rules are shadowed and won't be executed.
 *
 * ... But for now, we don't have a way to programmatically check it, so for now we need to
 * check the log file manually.
 *
 * TODO: Implement the test.
 */
@RunWith(AndroidJUnit4.class)
public class RavenwoodImplicitRuleShadowingTest extends RavenwoodImplicitRuleShadowingTestBase {
    @Test
    public void testOkInSubClass() {
    }
}
