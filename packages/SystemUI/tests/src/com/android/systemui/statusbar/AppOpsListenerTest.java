/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.os.Handler;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AppOpsListenerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationPresenter mPresenter;
    @Mock private AppOpsManager mAppOpsManager;

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private ForegroundServiceController mFsc;

    private AppOpsListener mListener;
    private Handler mHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(ForegroundServiceController.class, mFsc);
        getContext().addMockSystemService(AppOpsManager.class, mAppOpsManager);
        mHandler = new Handler(Looper.getMainLooper());
        when(mPresenter.getHandler()).thenReturn(mHandler);

        mListener = new AppOpsListener(mContext);
    }

    @Test
    public void testOnlyListenForFewOps() {
        mListener.setUpWithPresenter(mPresenter, mEntryManager);

        verify(mAppOpsManager, times(1)).startWatchingActive(AppOpsListener.OPS, mListener);
    }

    @Test
    public void testStopListening() {
        mListener.destroy();
        verify(mAppOpsManager, times(1)).stopWatchingActive(mListener);
    }

    @Test
    public void testInformEntryMgrOnAppOpsChange() {
        mListener.setUpWithPresenter(mPresenter, mEntryManager);
        mListener.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        waitForIdleSync(mHandler);
        verify(mEntryManager, times(1)).updateNotificationsForAppOp(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void testInformFscOnAppOpsChange() {
        mListener.setUpWithPresenter(mPresenter, mEntryManager);
        mListener.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        waitForIdleSync(mHandler);
        verify(mFsc, times(1)).onAppOpChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
    }
}
