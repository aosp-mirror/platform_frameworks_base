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

package com.android.ravenwoodtest.servicestest;

import static org.junit.Assert.assertEquals;

import android.platform.test.ravenwood.RavenwoodRule;
import android.ravenwood.example.BlueManager;
import android.ravenwood.example.RedManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RavenwoodServicesDependenciesTest {
    // NOTE: we carefully only ask for RedManager here, and rely on Ravenwood internals to spin
    // up the implicit dependency on BlueManager
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProcessSystem()
            .setServicesRequired(RedManager.class)
            .build();

    @Test
    public void testDirect() {
        final RedManager red = mRavenwood.getContext().getSystemService(
                RedManager.class);
        assertEquals("blue+red", red.getInterfaceDescriptor());
    }

    @Test
    public void testIndirect() {
        final BlueManager blue = mRavenwood.getContext().getSystemService(
                BlueManager.class);
        assertEquals("blue", blue.getInterfaceDescriptor());
    }
}
