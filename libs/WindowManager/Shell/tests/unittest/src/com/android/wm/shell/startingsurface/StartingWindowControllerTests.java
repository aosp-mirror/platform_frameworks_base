/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.startingsurface;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the starting window controller.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:StartingWindowControllerTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StartingWindowControllerTests extends ShellTestCase {

    private @Mock Context mContext;
    private @Mock DisplayManager mDisplayManager;
    private @Mock ShellInit mShellInit;
    private @Mock ShellTaskOrganizer mTaskOrganizer;
    private @Mock ShellExecutor mMainExecutor;
    private @Mock StartingWindowTypeAlgorithm mTypeAlgorithm;
    private @Mock IconProvider mIconProvider;
    private @Mock TransactionPool mTransactionPool;
    private StartingWindowController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mock(Display.class)).when(mDisplayManager).getDisplay(anyInt());
        doReturn(mDisplayManager).when(mContext).getSystemService(eq(DisplayManager.class));
        mController = new StartingWindowController(mContext, mShellInit, mTaskOrganizer,
                mMainExecutor, mTypeAlgorithm, mIconProvider, mTransactionPool);
    }

    @Test
    public void instantiate_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }
}
