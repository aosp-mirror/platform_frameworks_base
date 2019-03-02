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

import android.content.Context;
import android.os.Handler;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.AppOpsService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * runtest -c com.android.server.am.AppErrorDialogTest frameworks-services
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@FlakyTest(bugId = 113616538)
public class AppErrorDialogTest {

    private Context mContext;
    private ActivityManagerService mService;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mService = new ActivityManagerService(new ActivityManagerService.Injector() {
            @Override
            public AppOpsService getAppOpsService(File file, Handler handler) {
                return null;
            }

            @Override
            public Handler getUiHandler(ActivityManagerService service) {
                return null;
            }

            @Override
            public boolean isNetworkRestrictedForUid(int uid) {
                return false;
            }
        });
    }

    @Test
    @UiThreadTest
    public void testCreateWorks() throws Exception {
        AppErrorDialog.Data data = new AppErrorDialog.Data();
        data.proc = new ProcessRecord(null, null, mContext.getApplicationInfo(), "name", 12345);
        data.result = new AppErrorResult();

        AppErrorDialog dialog = new AppErrorDialog(mContext, mService, data);

        dialog.create();
    }
}
