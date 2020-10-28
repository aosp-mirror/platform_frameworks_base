/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:AppErrorDialogTest
 */
@SmallTest
@FlakyTest(bugId = 113616538)
public class AppErrorDialogTest {

    @BeforeClass
    public static void setUpOnce() {
        final PackageManagerInternal pm = mock(PackageManagerInternal.class);
        doReturn(new ComponentName("", "")).when(pm).getSystemUiServiceComponent();
        LocalServices.addService(PackageManagerInternal.class, pm);
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @Rule
    public ServiceThreadRule mServiceThreadRule = new ServiceThreadRule();

    private Context mContext;
    private ActivityManagerService mService;

    @Before
    public void setUp() throws Exception {
        mContext = getInstrumentation().getTargetContext();
        mService = new ActivityManagerService(new ActivityManagerService.Injector(mContext) {
            @Override
            public AppOpsService getAppOpsService(File file, Handler handler) {
                return null;
            }

            @Override
            public Handler getUiHandler(ActivityManagerService service) {
                return mServiceThreadRule.getThread().getThreadHandler();
            }

            @Override
            public boolean isNetworkRestrictedForUid(int uid) {
                return false;
            }
        }, mServiceThreadRule.getThread());
        mService.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mService.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
    }

    @Test
    @UiThreadTest
    public void testCreateWorks() {
        AppErrorDialog.Data data = new AppErrorDialog.Data();
        data.proc = new ProcessRecord(mService, mContext.getApplicationInfo(), "name", 12345);
        data.result = new AppErrorResult();

        AppErrorDialog dialog = new AppErrorDialog(mContext, mService, data);

        dialog.create();
    }
}
