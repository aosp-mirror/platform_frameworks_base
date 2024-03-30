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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
    @Mock
    private ActivityStarter mActivityStarter;
    private SystemUIDialog mDialog;
    private HearingDevicesDialogDelegate mDialogDelegate;

    @Before
    public void setUp() {
        when(mSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mSysUiState);

        setUpPairNewDeviceDialog();

        when(mSystemUIDialogFactory.create(any(SystemUIDialog.Delegate.class)))
                .thenReturn(mDialog);
    }

    @Test
    public void createDialog_dialogShown() {
        assertThat(mDialogDelegate.createDialog()).isEqualTo(mDialog);
    }

    @Test
    public void clickPairNewDeviceButton_intentActionMatch() {
        mDialog.show();

        getPairNewDeviceButton(mDialog).performClick();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mActivityStarter).postStartActivityDismissingKeyguard(intentCaptor.capture(),
                anyInt(), any());
        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                Settings.ACTION_HEARING_DEVICE_PAIRING_SETTINGS);
    }

    private void setUpPairNewDeviceDialog() {
        mDialogDelegate = new HearingDevicesDialogDelegate(
                true,
                mSystemUIDialogFactory,
                mActivityStarter,
                mDialogTransitionAnimator
        );
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
    }

    private View getPairNewDeviceButton(SystemUIDialog dialog) {
        return dialog.requireViewById(R.id.pair_new_device_button);
    }
}
