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
package com.android.ravenwoodtest.runtimetest;

import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
import static android.os.Process.FIRST_APPLICATION_UID;

import static org.junit.Assert.assertEquals;

import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodConfig;
import android.system.Os;

import com.android.ravenwood.RavenwoodRuntimeState;

import dalvik.system.VMRuntime;

import org.junit.Test;

public class IdentityTest {

    @RavenwoodConfig.Config
    public static final RavenwoodConfig sConfig =
            new RavenwoodConfig.Builder()
                    .setTargetSdkLevel(UPSIDE_DOWN_CAKE)
                    .setProcessApp()
                    .build();

    @Test
    public void testUid() {
        assertEquals(FIRST_APPLICATION_UID, RavenwoodRuntimeState.sUid);
        assertEquals(FIRST_APPLICATION_UID, Os.getuid());
        assertEquals(FIRST_APPLICATION_UID, Process.myUid());
        assertEquals(FIRST_APPLICATION_UID, Binder.getCallingUid());
    }

    @Test
    public void testPid() {
        int pid = RavenwoodRuntimeState.sPid;
        assertEquals(pid, Os.getpid());
        assertEquals(pid, Process.myPid());
        assertEquals(pid, Binder.getCallingPid());
    }

    @Test
    public void testTargetSdkLevel() {
        assertEquals(Build.VERSION_CODES.CUR_DEVELOPMENT, RavenwoodRuntimeState.CUR_DEVELOPMENT);
        assertEquals(UPSIDE_DOWN_CAKE, RavenwoodRuntimeState.sTargetSdkLevel);
        assertEquals(UPSIDE_DOWN_CAKE, VMRuntime.getRuntime().getTargetSdkVersion());
    }
}
