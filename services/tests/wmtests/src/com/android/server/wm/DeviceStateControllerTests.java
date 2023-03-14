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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;

/**
 * Test class for {@link DeviceStateController}.
 *
 * Build/Install/Run:
 *  atest WmTests:DeviceStateControllerTests
 */
@SmallTest
@Presubmit
public class DeviceStateControllerTests {

    private DeviceStateController.FoldStateListener mFoldStateListener;
    private DeviceStateController mTarget;
    private DeviceStateControllerBuilder mBuilder;

    private Context mMockContext;
    private Handler mMockHandler;
    private Resources mMockRes;
    private DeviceStateManager mMockDeviceStateManager;

    private Consumer<DeviceStateController.FoldState> mDelegate;
    private DeviceStateController.FoldState mCurrentState = DeviceStateController.FoldState.UNKNOWN;

    @Before
    public void setUp() {
        mBuilder = new DeviceStateControllerBuilder();
        mCurrentState = DeviceStateController.FoldState.UNKNOWN;
    }

    private void initialize(boolean supportFold, boolean supportHalfFold) throws Exception {
        mBuilder.setSupportFold(supportFold, supportHalfFold);
        mDelegate = (newFoldState) -> {
            mCurrentState = newFoldState;
        };
        mBuilder.setDelegate(mDelegate);
        mBuilder.build();
        verifyFoldStateListenerRegistration(1);
    }

    @Test
    public void testInitialization() throws Exception {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        mFoldStateListener.onStateChanged(mUnfoldedStates[0]);
        assertEquals(mCurrentState, DeviceStateController.FoldState.OPEN);
    }

    @Test
    public void testInitializationWithNoFoldSupport() throws Exception {
        initialize(false /* supportFold */, false /* supportHalfFolded */);
        mFoldStateListener.onStateChanged(mFoldedStates[0]);
        // Note that the folded state is ignored.
        assertEquals(mCurrentState, DeviceStateController.FoldState.OPEN);
    }

    @Test
    public void testWithFoldSupported() throws Exception {
        initialize(true /* supportFold */, false /* supportHalfFolded */);
        mFoldStateListener.onStateChanged(mUnfoldedStates[0]);
        assertEquals(mCurrentState, DeviceStateController.FoldState.OPEN);
        mFoldStateListener.onStateChanged(mFoldedStates[0]);
        assertEquals(mCurrentState, DeviceStateController.FoldState.FOLDED);
        mFoldStateListener.onStateChanged(mHalfFoldedStates[0]);
        assertEquals(mCurrentState, DeviceStateController.FoldState.OPEN); // Ignored
    }

    @Test
    public void testWithHalfFoldSupported() throws Exception {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        mFoldStateListener.onStateChanged(mUnfoldedStates[0]);
        assertEquals(mCurrentState, DeviceStateController.FoldState.OPEN);
        mFoldStateListener.onStateChanged(mFoldedStates[0]);
        assertEquals(mCurrentState, DeviceStateController.FoldState.FOLDED);
        mFoldStateListener.onStateChanged(mHalfFoldedStates[0]);
        assertEquals(mCurrentState, DeviceStateController.FoldState.HALF_FOLDED);
    }


    private final int[] mFoldedStates = {0};
    private final int[] mUnfoldedStates = {1};
    private final int[] mHalfFoldedStates = {2};


    private void verifyFoldStateListenerRegistration(int numOfInvocation) {
        final ArgumentCaptor<DeviceStateController.FoldStateListener> listenerCaptor =
                ArgumentCaptor.forClass(DeviceStateController.FoldStateListener.class);
        verify(mMockDeviceStateManager, times(numOfInvocation)).registerCallback(
                any(),
                listenerCaptor.capture());
        if (numOfInvocation > 0) {
            mFoldStateListener = listenerCaptor.getValue();
        }
    }

    private class DeviceStateControllerBuilder {
        private boolean mSupportFold = false;
        private boolean mSupportHalfFold = false;
        private Consumer<DeviceStateController.FoldState> mDelegate;

        DeviceStateControllerBuilder setSupportFold(
                boolean supportFold, boolean supportHalfFold) {
            mSupportFold = supportFold;
            mSupportHalfFold = supportHalfFold;
            return this;
        }

        DeviceStateControllerBuilder setDelegate(
                Consumer<DeviceStateController.FoldState> delegate) {
            mDelegate = delegate;
            return this;
        }

        private void mockFold(boolean enableFold, boolean enableHalfFold) {
            if (enableFold) {
                when(mMockContext.getResources().getIntArray(
                        com.android.internal.R.array.config_foldedDeviceStates))
                        .thenReturn(mFoldedStates);
            }
            if (enableHalfFold) {
                when(mMockContext.getResources().getIntArray(
                        com.android.internal.R.array.config_halfFoldedDeviceStates))
                        .thenReturn(mHalfFoldedStates);
            }
        }

        private void build() throws Exception {
            mMockContext = mock(Context.class);
            mMockRes = mock(Resources.class);
            when(mMockContext.getResources()).thenReturn((mMockRes));
            mMockDeviceStateManager = mock(DeviceStateManager.class);
            when(mMockContext.getSystemService(DeviceStateManager.class))
                    .thenReturn(mMockDeviceStateManager);
            mockFold(mSupportFold, mSupportHalfFold);
            mMockHandler = mock(Handler.class);
            mTarget = new DeviceStateController(mMockContext, mMockHandler, mDelegate);
        }
    }
}
