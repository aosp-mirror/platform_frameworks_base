/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.servertransaction;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.os.Handler;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link ClientTransactionListenerController}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ClientTransactionListenerControllerTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ClientTransactionListenerControllerTest {
    @Mock
    private IDisplayManager mIDisplayManager;
    @Mock
    private DisplayManager.DisplayListener mListener;

    private DisplayManagerGlobal mDisplayManager;
    private Handler mHandler;
    private ClientTransactionListenerController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDisplayManager = new DisplayManagerGlobal(mIDisplayManager);
        mHandler = getInstrumentation().getContext().getMainThreadHandler();
        mController = spy(ClientTransactionListenerController.createInstanceForTesting(
                mDisplayManager));
        doReturn(true).when(mController).isSyncWindowConfigUpdateFlagEnabled();
    }

    @Test
    public void testOnDisplayChanged() throws RemoteException {
        // Mock IDisplayManager to return a display info to trigger display change.
        final DisplayInfo newDisplayInfo = new DisplayInfo();
        doReturn(newDisplayInfo).when(mIDisplayManager).getDisplayInfo(123);

        mDisplayManager.registerDisplayListener(mListener, mHandler,
                DisplayManager.EVENT_FLAG_DISPLAY_CHANGED, null /* packageName */);

        mController.onDisplayChanged(123);
        mHandler.runWithScissors(() -> { }, 0);

        verify(mListener).onDisplayChanged(123);
    }
}
