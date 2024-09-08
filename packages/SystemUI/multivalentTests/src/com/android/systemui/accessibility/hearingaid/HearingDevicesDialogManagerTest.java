/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link HearingDevicesDialogManager}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesDialogManagerTest extends SysuiTestCase {

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private static final int TEST_LAUNCH_SOURCE_ID = 1;

    private final FakeExecutor mMainExecutor = new FakeExecutor(new FakeSystemClock());
    private final FakeExecutor mBackgroundExecutor = new FakeExecutor(new FakeSystemClock());
    @Mock
    private Expandable mExpandable;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private HearingDevicesDialogDelegate.Factory mDialogFactory;
    @Mock
    private HearingDevicesDialogDelegate mDialogDelegate;
    @Mock
    private SystemUIDialog mDialog;
    @Mock
    private HearingDevicesChecker mDevicesChecker;

    private HearingDevicesDialogManager mManager;

    @Before
    public void setUp() {
        when(mDialogFactory.create(anyBoolean(), anyInt())).thenReturn(mDialogDelegate);
        when(mDialogDelegate.createDialog()).thenReturn(mDialog);

        mManager = new HearingDevicesDialogManager(
                mDialogTransitionAnimator,
                mDialogFactory,
                mDevicesChecker,
                mBackgroundExecutor,
                mMainExecutor
        );
    }

    @Test
    public void showDialog_existHearingDevice_showPairNewDeviceFalse() {
        when(mDevicesChecker.isAnyPairedHearingDevice()).thenReturn(true);

        mManager.showDialog(mExpandable, TEST_LAUNCH_SOURCE_ID);
        mBackgroundExecutor.runAllReady();
        mMainExecutor.runAllReady();

        verify(mDialogFactory).create(eq(/* showPairNewDevice= */ false),
                eq(TEST_LAUNCH_SOURCE_ID));
    }

    @Test
    public void showDialog_noHearingDevice_showPairNewDeviceTrue() {
        when(mDevicesChecker.isAnyPairedHearingDevice()).thenReturn(false);

        mManager.showDialog(mExpandable, TEST_LAUNCH_SOURCE_ID);
        mBackgroundExecutor.runAllReady();
        mMainExecutor.runAllReady();

        verify(mDialogFactory).create(eq(/* showPairNewDevice= */ true), eq(TEST_LAUNCH_SOURCE_ID));
    }
}
