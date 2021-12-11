/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.communal.CommunalManager;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.condition.Monitor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalManagerUpdaterTest extends SysuiTestCase {
    private CommunalSourceMonitor mMonitor;
    @Mock
    private CommunalManager mCommunalManager;
    @Mock
    private Monitor mCommunalConditionsMonitor;

    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(CommunalManager.class, mCommunalManager);

        doAnswer(invocation -> {
            final Monitor.Callback callback = invocation.getArgument(0);
            callback.onConditionsChanged(true);
            return null;
        }).when(mCommunalConditionsMonitor).addCallback(any());

        mMonitor = new CommunalSourceMonitor(mExecutor, mCommunalConditionsMonitor);
        final CommunalManagerUpdater updater = new CommunalManagerUpdater(mContext, mMonitor);
        updater.start();
        clearInvocations(mCommunalManager);
    }

    @Test
    public void testUpdateSystemService_false() {
        mMonitor.setSource(null);
        mExecutor.runAllReady();
        verify(mCommunalManager).setCommunalViewShowing(false);
    }

    @Test
    public void testUpdateSystemService_true() {
        final CommunalSource source = mock(CommunalSource.class);
        mMonitor.setSource(source);
        mExecutor.runAllReady();
        verify(mCommunalManager).setCommunalViewShowing(true);
    }
}
