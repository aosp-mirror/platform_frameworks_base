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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

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
    private ProcessErrorStateRecord mProcessErrorState;

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
            final AppProfiler profiler = mock(AppProfiler.class);
            setFieldValue(AppProfiler.class, profiler, "mProfilerLock", new Object());
            setFieldValue(ActivityManagerService.class, sService, "mAppProfiler", profiler);
            setFieldValue(ActivityManagerService.class, sService, "mProcLock",
                    new ActivityManagerProcLock());
            final ProcessList processList = new ProcessList();
            setFieldValue(ActivityManagerService.class, sService, "mProcessList", processList);
        });

        // Avoid NPE when initializing {@link ProcessRecord#mWindowProcessController}.
        final PackageManagerInternal packageManagerInternal = mock(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, packageManagerInternal);
        final ComponentName sysUiName = new ComponentName(sContext.getPackageName(), "test");
        doReturn(sysUiName).when(packageManagerInternal).getSystemUiServiceComponent();
    }

    private static <T> void setFieldValue(Class clazz, Object obj, String fieldName, T val) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Field mfield = Field.class.getDeclaredField("accessFlags");
            mfield.setAccessible(true);
            mfield.setInt(field, mfield.getInt(field) & ~(Modifier.FINAL | Modifier.PRIVATE));
            field.set(obj, val);
        } catch (NoSuchFieldException | IllegalAccessException e) {
        }
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @Before
    public void setUpProcess() throws Exception {
        // Need to run with dexmaker share class loader to mock package private class.
        runWithDexmakerShareClassLoader(() -> {
            mProcessRecord = new ProcessRecord(sService, sContext.getApplicationInfo(),
                    "name", 12345);
            mProcessErrorState = spy(mProcessRecord.mErrorState);
            doNothing().when(mProcessErrorState).startAppProblemLSP();
            doReturn(false).when(mProcessErrorState).isSilentAnr();
            doReturn(false).when(mProcessErrorState).isMonitorCpuUsage();
        });
    }


    /**
     * This test verifies the process default status. If this doesn't pass, none of the other tests
     * should be able to pass.
     */
    @Test
    public void testProcessDefaultAnrRelatedStatus() {
        assertFalse(mProcessErrorState.isNotResponding());
        assertFalse(mProcessErrorState.isCrashing());
        assertFalse(mProcessRecord.isKilledByAm());
        assertFalse(mProcessRecord.isKilled());
    }

    /**
     * This test verifies that if the process is crashing, Anr will do nothing.
     */
    @Test
    public void testAnrWhenCrash() {
        mProcessErrorState.setCrashing(true);
        assertTrue(mProcessErrorState.isCrashing());
        appNotResponding(mProcessErrorState, "Test ANR when crash");
        assertFalse(mProcessErrorState.isNotResponding());
        assertFalse(mProcessRecord.isKilledByAm());
        assertFalse(mProcessRecord.isKilled());
    }

    /**
     * This test verifies that if the process is killed by AM, Anr will do nothing.
     */
    @Test
    public void testAnrWhenKilledByAm() {
        mProcessRecord.setKilledByAm(true);
        appNotResponding(mProcessErrorState, "Test ANR when killed by AM");
        assertFalse(mProcessErrorState.isNotResponding());
        assertFalse(mProcessErrorState.isCrashing());
        assertFalse(mProcessRecord.isKilled());
    }

    /**
     * This test verifies that if the process is killed, Anr will do nothing.
     */
    @Test
    public void testAnrWhenKilled() {
        mProcessRecord.setKilled(true);
        appNotResponding(mProcessErrorState, "Test ANR when killed");
        assertFalse(mProcessErrorState.isNotResponding());
        assertFalse(mProcessErrorState.isCrashing());
        assertFalse(mProcessRecord.isKilledByAm());
    }

    /**
     * This test verifies that non-silent ANR can run through successfully and the corresponding
     * flags can be set correctly.
     */
    @Test
    public void testNonSilentAnr() {
        appNotResponding(mProcessErrorState, "Test non-silent ANR");
        assertTrue(mProcessErrorState.isNotResponding());
        assertFalse(mProcessErrorState.isCrashing());
        assertFalse(mProcessRecord.isKilledByAm());
        assertFalse(mProcessRecord.isKilled());
    }

    /**
     * This test verifies that silent ANR can run through successfully and the corresponding flags
     * can be set correctly.
     */
    @Test
    public void testSilentAnr() {
        // Silent Anr will run through even without a parent process, and directly killed by AM.
        doReturn(true).when(mProcessErrorState).isSilentAnr();
        appNotResponding(mProcessErrorState, "Test silent ANR");
        assertTrue(mProcessErrorState.isNotResponding());
        assertFalse(mProcessErrorState.isCrashing());
        assertTrue(mProcessRecord.isKilledByAm());
        assertTrue(mProcessRecord.isKilled());
    }

    private static void appNotResponding(ProcessErrorStateRecord processErrorState,
            String annotation) {
        processErrorState.appNotResponding(null /* activityShortComponentName */, null /* aInfo */,
                null /* parentShortComponentName */, null /* parentProcess */,
                false /* aboveSystem */, annotation, false /* onlyDumpSelf */);
    }
}
