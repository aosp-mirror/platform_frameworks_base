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

package com.android.systemui.wmshell;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.pip.Pip;
import com.android.systemui.pip.tv.PipController;
import com.android.systemui.stackdivider.SplitScreen;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.tracing.ProtoTracer;
import com.android.wm.shell.common.DisplayImeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WMShellTest extends SysuiTestCase {

    Instrumentation mInstrumentation;
    WMShell mWMShell;
    @Mock Context mContext;
    @Mock CommandQueue mCommandQueue;
    @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock DisplayImeController mDisplayImeController;
    @Mock Optional<Pip> mPipOptional;
    @Mock Optional<SplitScreen> mSplitScreenOptional;
    @Mock PipController mPipController;
    @Mock ProtoTracer mProtoTracer;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        MockitoAnnotations.initMocks(this);
        mWMShell = new WMShell(mContext, mCommandQueue, mKeyguardUpdateMonitor,
                mDisplayImeController, mPipOptional, mSplitScreenOptional, mProtoTracer);
        mWMShell.start();
        when(mPipOptional.get()).thenReturn(mPipController);
    }

    @Test
    public void testWMShellRegisterCommandQueue() {
        verify(mCommandQueue, times(1)).addCallback(mWMShell);
    }
}
