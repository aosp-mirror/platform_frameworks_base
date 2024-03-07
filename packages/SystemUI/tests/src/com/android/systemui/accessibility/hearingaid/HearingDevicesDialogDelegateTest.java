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

import static com.android.systemui.statusbar.phone.SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.model.SysUiState;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link HearingDevicesDialogDelegate}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesDialogDelegateTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private SystemUIDialog.Factory mSystemUIDialogFactory;
    @Mock
    private SystemUIDialogManager mSystemUIDialogManager;
    @Mock
    private SysUiState mSysUiState;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    private SystemUIDialog mDialog;
    private HearingDevicesDialogDelegate mDialogDelegate;

    @Before
    public void setUp() {
        mDialogDelegate = new HearingDevicesDialogDelegate(mSystemUIDialogFactory);
        mDialog = new SystemUIDialog(
                mContext,
                0,
                DEFAULT_DISMISS_ON_DEVICE_LOCK,
                mSystemUIDialogManager,
                mSysUiState,
                getFakeBroadcastDispatcher(),
                mDialogTransitionAnimator,
                mDialogDelegate
        );

        when(mSystemUIDialogFactory.create(any(SystemUIDialog.Delegate.class)))
                .thenReturn(mDialog);
    }

    @Test
    public void createDialog_dialogShown() {
        assertThat(mDialogDelegate.createDialog()).isEqualTo(mDialog);
    }
}
