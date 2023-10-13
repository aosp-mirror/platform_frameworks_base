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

package com.android.server.display.notifications;

import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_CAPABLE_DISABLED;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_ENABLED;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE;
import static android.hardware.usb.DisplayPortAltModeInfo.LINK_TRAINING_STATUS_FAILURE;

import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.usb.DisplayPortAltModeInfo;
import android.hardware.usb.UsbManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.notifications.ConnectedDisplayUsbErrorsDetector.Injector;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link ConnectedDisplayUsbErrorsDetector}
 * Run: atest ConnectedDisplayUsbErrorsDetectorTest
 */
@SmallTest
@RunWith(TestParameterInjector.class)
public class ConnectedDisplayUsbErrorsDetectorTest {
    @Mock
    private Injector mMockedInjector;
    @Mock
    private UsbManager mMockedUsbManager;
    @Mock
    private DisplayManagerFlags mMockedFlags;
    @Mock
    private ConnectedDisplayUsbErrorsDetector.Listener mMockedListener;

    /** Setup tests. */
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testNoErrorTypes(
            @TestParameter final boolean isUsbManagerAvailable,
            @TestParameter final boolean isUsbErrorsNotificationEnabled) {
        // This is tested in #testErrorOnUsbCableNotCapableDp and #testErrorOnDpLinkTrainingFailure
        assumeFalse(isUsbManagerAvailable && isUsbErrorsNotificationEnabled);
        var detector = createErrorsDetector(isUsbManagerAvailable, isUsbErrorsNotificationEnabled);
        // None of these should trigger an error now.
        detector.onDisplayPortAltModeInfoChanged("portId", createInfoOnUsbCableNotCapableDp());
        detector.onDisplayPortAltModeInfoChanged("portId", createInfoOnDpLinkTrainingFailure());
        verify(mMockedUsbManager, never()).registerDisplayPortAltModeInfoListener(any(), any());
        verify(mMockedListener, never()).onCableNotCapableDisplayPort();
        verify(mMockedListener, never()).onDisplayPortLinkTrainingFailure();
    }

    @Test
    public void testErrorOnUsbCableNotCapableDp() {
        var detector = createErrorsDetector(/*isUsbManagerAvailable=*/ true,
                /*isUsbErrorsNotificationEnabled=*/ true);
        detector.onDisplayPortAltModeInfoChanged("portId", createInfoOnUsbCableNotCapableDp());
        verify(mMockedUsbManager).registerDisplayPortAltModeInfoListener(any(), any());
        verify(mMockedListener).onCableNotCapableDisplayPort();
        verify(mMockedListener, never()).onDisplayPortLinkTrainingFailure();
    }

    @Test
    public void testErrorOnDpLinkTrainingFailure() {
        var detector = createErrorsDetector(/*isUsbManagerAvailable=*/ true,
                /*isUsbErrorsNotificationEnabled=*/ true);
        detector.onDisplayPortAltModeInfoChanged("portId", createInfoOnDpLinkTrainingFailure());
        verify(mMockedUsbManager).registerDisplayPortAltModeInfoListener(any(), any());
        verify(mMockedListener, never()).onCableNotCapableDisplayPort();
        verify(mMockedListener).onDisplayPortLinkTrainingFailure();
    }

    private ConnectedDisplayUsbErrorsDetector createErrorsDetector(
            final boolean isUsbManagerAvailable,
            final boolean isConnectedDisplayUsbErrorsNotificationEnabled) {
        when(mMockedFlags.isConnectedDisplayErrorHandlingEnabled())
                .thenReturn(isConnectedDisplayUsbErrorsNotificationEnabled);
        when(mMockedInjector.getUsbManager()).thenReturn(
                (isUsbManagerAvailable) ? mMockedUsbManager : null);
        var detector = new ConnectedDisplayUsbErrorsDetector(mMockedFlags,
                ApplicationProvider.getApplicationContext(), mMockedInjector);
        detector.registerListener(mMockedListener);
        return detector;
    }

    private DisplayPortAltModeInfo createInfoOnUsbCableNotCapableDp() {
        return new DisplayPortAltModeInfo(
                    DISPLAYPORT_ALT_MODE_STATUS_CAPABLE_DISABLED,
                    DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE, -1, false, 0);
    }

    private DisplayPortAltModeInfo createInfoOnDpLinkTrainingFailure() {
        return new DisplayPortAltModeInfo(
                    DISPLAYPORT_ALT_MODE_STATUS_CAPABLE_DISABLED,
                    DISPLAYPORT_ALT_MODE_STATUS_ENABLED, -1, false,
                    LINK_TRAINING_STATUS_FAILURE);
    }
}
