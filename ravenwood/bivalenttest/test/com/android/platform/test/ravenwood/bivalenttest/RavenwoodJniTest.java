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
package com.android.platform.test.ravenwood.bivalenttest;

import static junit.framework.Assert.assertEquals;

import android.platform.test.ravenwood.RavenwoodRule;
import android.platform.test.ravenwood.RavenwoodUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RavenwoodJniTest {
    static {
        RavenwoodUtils.loadJniLibrary("ravenwoodbivalenttest_jni");
    }

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder().build();

    private static native int add(int a, int b);

    @Test
    public void testNativeMethod() {
        assertEquals(5, add(2, 3));
    }
}
