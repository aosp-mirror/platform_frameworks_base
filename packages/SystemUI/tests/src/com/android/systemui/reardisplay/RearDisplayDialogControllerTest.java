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

package com.android.systemui.reardisplay;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotSame;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceStateManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.model.SysUiState;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class RearDisplayDialogControllerTest extends SysuiTestCase {

    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private SystemUIDialog.Factory mSystemUIDialogFactory;
    @Mock
    private SystemUIDialog mSystemUIDialog;
    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    @Mock
    private SysUiState mSysUiState;
    @Mock
    private Resources mResources;

    LayoutInflater mLayoutInflater = LayoutInflater.from(mContext);

    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private static final int CLOSED_BASE_STATE = 0;
    private static final int OPEN_BASE_STATE = 1;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mSysUiState);
        when(mSystemUIDialogFactory.create()).thenReturn(mSystemUIDialog);
        when(mSystemUIDialog.getContext()).thenReturn(mContext);
    }
    @Test
    public void testClosedDialogIsShown() {
        RearDisplayDialogController controller = new RearDisplayDialogController(
                mCommandQueue,
                mFakeExecutor,
                mResources,
                mLayoutInflater,
                mSystemUIDialogFactory);
        controller.setDeviceStateManagerCallback(new TestDeviceStateManagerCallback());
        controller.setFoldedStates(new int[]{0});
        controller.setAnimationRepeatCount(0);

        controller.showRearDisplayDialog(CLOSED_BASE_STATE);
        verify(mSystemUIDialog).show();

        View container = getDialogViewContainer();
        TextView deviceClosedTitleTextView = container.findViewById(
                R.id.rear_display_title_text_view);
        assertEquals(deviceClosedTitleTextView.getText().toString(),
                getContext().getResources().getString(
                        R.string.rear_display_folded_bottom_sheet_title));
    }

    @Test
    public void testClosedDialogIsRefreshedOnConfigurationChange() {
        RearDisplayDialogController controller = new RearDisplayDialogController(
                mCommandQueue,
                mFakeExecutor,
                mResources,
                mLayoutInflater,
                mSystemUIDialogFactory);
        controller.setDeviceStateManagerCallback(new TestDeviceStateManagerCallback());
        controller.setFoldedStates(new int[]{0});
        controller.setAnimationRepeatCount(0);

        controller.showRearDisplayDialog(CLOSED_BASE_STATE);
        verify(mSystemUIDialog).show();
        View container = getDialogViewContainer();
        TextView deviceClosedTitleTextView = container.findViewById(
                R.id.rear_display_title_text_view);

        reset(mSystemUIDialog);
        when(mSystemUIDialog.isShowing()).thenReturn(true);
        when(mSystemUIDialog.getContext()).thenReturn(mContext);

        controller.onConfigChanged(new Configuration());
        TextView deviceClosedTitleTextView2 = container.findViewById(
                R.id.rear_display_title_text_view);

        assertNotSame(deviceClosedTitleTextView, deviceClosedTitleTextView2);
    }

    @Test
    public void testOpenDialogIsShown() {
        RearDisplayDialogController controller = new RearDisplayDialogController(
                mCommandQueue,
                mFakeExecutor,
                mResources,
                mLayoutInflater,
                mSystemUIDialogFactory);
        controller.setDeviceStateManagerCallback(new TestDeviceStateManagerCallback());
        controller.setFoldedStates(new int[]{0});
        controller.setAnimationRepeatCount(0);

        controller.showRearDisplayDialog(OPEN_BASE_STATE);

        verify(mSystemUIDialog).show();
        View container = getDialogViewContainer();
        TextView deviceClosedTitleTextView = container.findViewById(
                R.id.rear_display_title_text_view);
        assertEquals(deviceClosedTitleTextView.getText().toString(),
                getContext().getResources().getString(
                        R.string.rear_display_unfolded_bottom_sheet_title));
    }

    private View getDialogViewContainer() {
        ArgumentCaptor<View> viewCaptor = ArgumentCaptor.forClass(View.class);
        verify(mSystemUIDialog).setView(viewCaptor.capture());

        return viewCaptor.getValue();
    }
    /**
     * Empty device state manager callbacks, so we can verify that the correct
     * dialogs are being created regardless of device state of the test device.
     */
    private static class TestDeviceStateManagerCallback implements
            DeviceStateManager.DeviceStateCallback {

        @Override
        public void onStateChanged(int state) { }
    }
}
