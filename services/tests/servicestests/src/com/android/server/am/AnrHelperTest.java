/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.appop.AppOpsService;
import com.android.server.wm.WindowProcessController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:AnrHelperTest
 */
@SmallTest
@Presubmit
public class AnrHelperTest {
    private AnrHelper mAnrHelper;

    private ProcessRecord mAnrApp;

    @Rule
    public ServiceThreadRule mServiceThreadRule = new ServiceThreadRule();

    @Before
    public void setUp() {
        final Context context = getInstrumentation().getTargetContext();
        runWithDexmakerShareClassLoader(() -> {
            mAnrApp = mock(ProcessRecord.class);
            final ProcessErrorStateRecord errorState = mock(ProcessErrorStateRecord.class);
            setFieldValue(ProcessErrorStateRecord.class, errorState, "mProcLock",
                    new ActivityManagerProcLock());
            setFieldValue(ProcessRecord.class, mAnrApp, "mErrorState", errorState);
            final ActivityManagerService service = new ActivityManagerService(
                    new ActivityManagerService.Injector(context) {
                    @Override
                    public AppOpsService getAppOpsService(File file, Handler handler) {
                        return null;
                    }

                    @Override
                    public Handler getUiHandler(ActivityManagerService service) {
                        return mServiceThreadRule.getThread().getThreadHandler();
                    }
                }, mServiceThreadRule.getThread());
            mAnrHelper = new AnrHelper(service);
        });
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

    @Test
    public void testHandleAppNotResponding() {
        final String activityShortComponentName = "pkg.test/.A";
        final String parentShortComponentName = "pkg.test/.P";
        final ApplicationInfo appInfo = new ApplicationInfo();
        final WindowProcessController parentProcess = mock(WindowProcessController.class);
        final boolean aboveSystem = false;
        final String annotation = "test";
        mAnrHelper.appNotResponding(mAnrApp, activityShortComponentName, appInfo,
                parentShortComponentName, parentProcess, aboveSystem, annotation);

        verify(mAnrApp.mErrorState, timeout(TimeUnit.SECONDS.toMillis(5))).appNotResponding(
                eq(activityShortComponentName), eq(appInfo), eq(parentShortComponentName),
                eq(parentProcess), eq(aboveSystem), eq(annotation), eq(false) /* onlyDumpSelf */);
    }
}
