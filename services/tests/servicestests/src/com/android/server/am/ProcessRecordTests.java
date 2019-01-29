/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.am;

import static android.testing.DexmakerShareClassLoaderRule.runWithDexmakerShareClassLoader;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;

import com.android.server.wm.ActivityTaskManagerService;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ProcessRecordTests
 */
@Presubmit
@FlakyTest(detail = "Promote to presubmit when shown to be stable.")
public class ProcessRecordTests {
    private static Context sContext;
    private static ActivityManagerService sService;

    private ProcessRecord mProcessRecord;

    @BeforeClass
    public static void setUpOnce() throws Exception {
        sContext = getInstrumentation().getTargetContext();

        // We need to run with dexmaker share class loader to make use of ActivityTaskManagerService
        // from wm package.
        runWithDexmakerShareClassLoader(() -> {
            sService = mock(ActivityManagerService.class);
            sService.mActivityTaskManager = new ActivityTaskManagerService(sContext);
            sService.mActivityTaskManager.initialize(null, null, sContext.getMainLooper());
            sService.mAtmInternal = sService.mActivityTaskManager.getAtmInternal();
        });
    }

    @Before
    public void setUpProcess() throws Exception {
        // Need to run with dexmaker share class loader to mock package private class.
        runWithDexmakerShareClassLoader(() -> {
            mProcessRecord = spy(new ProcessRecord(sService, sContext.getApplicationInfo(),
                    "name", 12345));
            doNothing().when(mProcessRecord).startAppProblemLocked();
            doReturn(false).when(mProcessRecord).isSilentAnr();
            doReturn(false).when(mProcessRecord).isMonitorCpuUsage();
            doReturn(Collections.emptyList()).when(mProcessRecord).getLruProcessList();
        });
    }


    /**
     * This test verifies the process default status. If this doesn't pass, none of the other tests
     * should be able to pass.
     */
    @Test
    public void testProcessDefaultAnrRelatedStatus() {
        assertFalse(mProcessRecord.isNotResponding());
        assertFalse(mProcessRecord.isCrashing());
        assertFalse(mProcessRecord.killedByAm);
        assertFalse(mProcessRecord.killed);
    }

    /**
     * This test verifies that if the process is crashing, Anr will do nothing.
     */
    @Test
    public void testAnrWhenCrash() {
        mProcessRecord.setCrashing(true);
        assertTrue(mProcessRecord.isCrashing());
        mProcessRecord.appNotResponding(null, null, null, null, false, "Test ANR when crash");
        assertFalse(mProcessRecord.isNotResponding());
        assertFalse(mProcessRecord.killedByAm);
        assertFalse(mProcessRecord.killed);
    }

    /**
     * This test verifies that if the process is killed by AM, Anr will do nothing.
     */
    @Test
    public void testAnrWhenKilledByAm() {
        mProcessRecord.killedByAm = true;
        mProcessRecord.appNotResponding(null, null, null, null, false,
                "Test ANR when killed by AM");
        assertFalse(mProcessRecord.isNotResponding());
        assertFalse(mProcessRecord.isCrashing());
        assertFalse(mProcessRecord.killed);
    }

    /**
     * This test verifies that if the process is killed, Anr will do nothing.
     */
    @Test
    public void testAnrWhenKilled() {
        mProcessRecord.killed = true;
        mProcessRecord.appNotResponding(null, null, null, null, false, "Test ANR when killed");
        assertFalse(mProcessRecord.isNotResponding());
        assertFalse(mProcessRecord.isCrashing());
        assertFalse(mProcessRecord.killedByAm);
    }

    /**
     * This test verifies that non-silent ANR can run through successfully and the corresponding
     * flags can be set correctly.
     */
    @Test
    public void testNonSilentAnr() {
        mProcessRecord.appNotResponding(null, null, null, null, false, "Test non-silent ANR");
        assertTrue(mProcessRecord.isNotResponding());
        assertFalse(mProcessRecord.isCrashing());
        assertFalse(mProcessRecord.killedByAm);
        assertFalse(mProcessRecord.killed);
    }

    /**
     * This test verifies that silent ANR can run through successfully and the corresponding flags
     * can be set correctly.
     */
    @Test
    public void testSilentAnr() {
        // Silent Anr will run through even without a parent process, and directly killed by AM.
        doReturn(true).when(mProcessRecord).isSilentAnr();
        mProcessRecord.appNotResponding(null, null, null, null, false, "Test silent ANR");
        assertTrue(mProcessRecord.isNotResponding());
        assertFalse(mProcessRecord.isCrashing());
        assertTrue(mProcessRecord.killedByAm);
        assertTrue(mProcessRecord.killed);
    }
}
