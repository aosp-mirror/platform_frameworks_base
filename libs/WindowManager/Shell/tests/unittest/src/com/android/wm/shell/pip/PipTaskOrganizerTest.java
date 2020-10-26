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

package com.android.wm.shell.pip;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.splitscreen.SplitScreen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Unit tests for {@link PipTaskOrganizer}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class PipTaskOrganizerTest extends PipTestCase {
    private PipTaskOrganizer mSpiedPipTaskOrganizer;

    @Mock private DisplayController mMockdDisplayController;
    @Mock private PipBoundsHandler mMockPipBoundsHandler;
    @Mock private PipSurfaceTransactionHelper mMockPipSurfaceTransactionHelper;
    @Mock private PipUiEventLogger mMockPipUiEventLogger;
    @Mock private Optional<SplitScreen> mMockOptionalSplitScreen;
    @Mock private ShellTaskOrganizer mMockShellTaskOrganizer;
    @Mock private PipBoundsState mMockPipBoundsState;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mSpiedPipTaskOrganizer = new PipTaskOrganizer(mContext, mMockPipBoundsState,
                mMockPipBoundsHandler, mMockPipSurfaceTransactionHelper, mMockOptionalSplitScreen,
                mMockdDisplayController, mMockPipUiEventLogger, mMockShellTaskOrganizer);
    }

    @Test
    public void instantiatePipTaskOrganizer_addsTaskListener() {
        verify(mMockShellTaskOrganizer).addListenerForType(any(), anyInt());
    }

    @Test
    public void instantiatePipTaskOrganizer_addsDisplayWindowListener() {
        verify(mMockdDisplayController).addDisplayWindowListener(any());
    }
}
