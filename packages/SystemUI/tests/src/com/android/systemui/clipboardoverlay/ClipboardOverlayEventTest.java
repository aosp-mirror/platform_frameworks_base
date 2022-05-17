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

package com.android.systemui.clipboardoverlay;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_ENABLED;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.DeviceConfigProxyFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardOverlayEventTest extends SysuiTestCase {

    @Mock
    private ClipboardManager mClipboardManager;
    @Mock
    private ClipboardOverlayControllerFactory mClipboardOverlayControllerFactory;
    @Mock
    private ClipboardOverlayController mOverlayController;
    @Mock
    private UiEventLogger mUiEventLogger;

    private final String mSampleSource = "Example source";

    private ClipboardListener mClipboardListener;


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mClipboardOverlayControllerFactory.create(any())).thenReturn(
                mOverlayController);
        when(mClipboardManager.hasPrimaryClip()).thenReturn(true);

        ClipData sampleClipData = new ClipData("Test", new String[]{"text/plain"},
                new ClipData.Item("Test Item"));
        when(mClipboardManager.getPrimaryClip()).thenReturn(sampleClipData);
        when(mClipboardManager.getPrimaryClipSource()).thenReturn(mSampleSource);

        DeviceConfigProxyFake deviceConfigProxy = new DeviceConfigProxyFake();
        deviceConfigProxy.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_ENABLED,
                "true", false);

        mClipboardListener = new ClipboardListener(getContext(), deviceConfigProxy,
                mClipboardOverlayControllerFactory, mClipboardManager, mUiEventLogger);
    }

    @Test
    public void test_enterAndReenter() {
        mClipboardListener.start();

        mClipboardListener.onPrimaryClipChanged();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED, 0, mSampleSource);
        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_UPDATED, 0, mSampleSource);
    }
}
