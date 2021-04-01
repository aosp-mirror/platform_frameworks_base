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

package com.android.systemui.qs;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.globalactions.GlobalActionsDialogLite;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.MultiUserSwitch;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.utils.leaks.FakeTunerService;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QSFooterViewControllerTest extends LeakCheckedTest {

    @Mock
    private QSFooterView mView;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private DeviceProvisionedController mDeviceProvisionedController;
    @Mock
    private UserInfoController mUserInfoController;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private QSPanelController mQSPanelController;
    @Mock
    private ClipboardManager mClipboardManager;
    @Mock
    private QuickQSPanelController mQuickQSPanelController;
    private FakeTunerService mFakeTunerService;
    private MetricsLogger mMetricsLogger = new FakeMetricsLogger();

    @Mock
    private SettingsButton mSettingsButton;
    @Mock
    private TextView mBuildText;
    @Mock
    private View mEdit;
    @Mock
    private MultiUserSwitch mMultiUserSwitch;
    @Mock
    private View mPowerMenuLiteView;
    @Mock
    private GlobalActionsDialogLite mGlobalActionsDialog;

    private QSFooterViewController mController;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);

        mFakeTunerService = (FakeTunerService) Dependency.get(TunerService.class);

        mContext.addMockSystemService(ClipboardManager.class, mClipboardManager);

        when(mView.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mContext.getResources());
        when(mUserTracker.getUserContext()).thenReturn(mContext);

        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mView.findViewById(R.id.settings_button)).thenReturn(mSettingsButton);
        when(mView.findViewById(R.id.build)).thenReturn(mBuildText);
        when(mView.findViewById(android.R.id.edit)).thenReturn(mEdit);
        when(mView.findViewById(R.id.multi_user_switch)).thenReturn(mMultiUserSwitch);
        when(mView.findViewById(R.id.pm_lite)).thenReturn(mPowerMenuLiteView);

        mController = new QSFooterViewController(mView, mUserManager, mUserInfoController,
                mActivityStarter, mDeviceProvisionedController, mUserTracker, mQSPanelController,
                new QSDetailDisplayer(), mQuickQSPanelController, mFakeTunerService,
                mMetricsLogger, false, mGlobalActionsDialog);

        mController.init();
    }

    @Test
    public void testBuildTextCopy() {
        String text = "TEST";
        ArgumentCaptor<View.OnLongClickListener> onLongClickCaptor =
                ArgumentCaptor.forClass(View.OnLongClickListener.class);

        verify(mBuildText).setOnLongClickListener(onLongClickCaptor.capture());

        when(mBuildText.getText()).thenReturn(text);
        onLongClickCaptor.getValue().onLongClick(mBuildText);

        ArgumentCaptor<ClipData> captor = ArgumentCaptor.forClass(ClipData.class);
        verify(mClipboardManager).setPrimaryClip(captor.capture());
        assertThat(captor.getValue().getItemAt(0).getText()).isEqualTo(text);
    }

    @Test
    public void testSettings_UserNotSetup() {
        ArgumentCaptor<View.OnClickListener> onClickCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(mSettingsButton).setOnClickListener(onClickCaptor.capture());

        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);

        onClickCaptor.getValue().onClick(mSettingsButton);
        // Verify Settings wasn't launched.
        verify(mActivityStarter, never()).startActivity(any(), anyBoolean());
    }
}
