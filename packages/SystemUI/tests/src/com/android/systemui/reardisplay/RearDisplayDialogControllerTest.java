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

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import android.hardware.devicestate.DeviceStateManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class RearDisplayDialogControllerTest extends SysuiTestCase {

    @Mock
    private CommandQueue mCommandQueue;

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());


    private static final int CLOSED_BASE_STATE = 0;
    private static final int OPEN_BASE_STATE = 1;

    @Test
    public void testClosedDialogIsShown() {
        RearDisplayDialogController controller = new RearDisplayDialogController(mContext,
                mCommandQueue, mFakeExecutor);
        controller.setDeviceStateManagerCallback(new TestDeviceStateManagerCallback());
        controller.setFoldedStates(new int[]{0});
        controller.setAnimationRepeatCount(0);

        controller.showRearDisplayDialog(CLOSED_BASE_STATE);
        assertTrue(controller.mRearDisplayEducationDialog.isShowing());
        View deviceOpenedWarningTextView = controller.mRearDisplayEducationDialog.findViewById(
                R.id.rear_display_warning_text_view);
        assertNull(deviceOpenedWarningTextView);
    }

    @Test
    public void testOpenDialogIsShown() {
        RearDisplayDialogController controller = new RearDisplayDialogController(mContext,
                mCommandQueue, mFakeExecutor);
        controller.setDeviceStateManagerCallback(new TestDeviceStateManagerCallback());
        controller.setFoldedStates(new int[]{0});
        controller.setAnimationRepeatCount(0);

        controller.showRearDisplayDialog(OPEN_BASE_STATE);

        assertTrue(controller.mRearDisplayEducationDialog.isShowing());
        View deviceOpenedWarningTextView = controller.mRearDisplayEducationDialog.findViewById(
                R.id.rear_display_warning_text_view);
        assertNotNull(deviceOpenedWarningTextView);
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
