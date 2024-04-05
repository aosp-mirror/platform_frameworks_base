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

import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodClassRule;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@DisabledOnRavenwood
public class RavenwoodClassRuleDeviceOnlyTest {
    @ClassRule
    public static final RavenwoodClassRule sRavenwood = new RavenwoodClassRule();

    @Test
    public void testDeviceOnly() {
        Assert.assertFalse(RavenwoodRule.isOnRavenwood());
    }
}
