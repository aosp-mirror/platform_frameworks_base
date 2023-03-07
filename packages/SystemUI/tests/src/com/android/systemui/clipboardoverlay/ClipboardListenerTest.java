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

import static com.google.android.setupcompat.util.WizardManagerHelper.SETTINGS_SECURE_USER_SETUP_COMPLETE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.PersistableBundle;
import android.provider.DeviceConfig;
import android.provider.Settings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.DeviceConfigProxyFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardListenerTest extends SysuiTestCase {

    @Mock
    private ClipboardManager mClipboardManager;
    @Mock
    private ClipboardOverlayControllerFactory mClipboardOverlayControllerFactory;
    @Mock
    private ClipboardOverlayController mOverlayController;
    @Mock
    private ClipboardToast mClipboardToast;
    @Mock
    private UiEventLogger mUiEventLogger;
    private DeviceConfigProxyFake mDeviceConfigProxy;

    private ClipData mSampleClipData;
    private String mSampleSource = "Example source";

    @Captor
    private ArgumentCaptor<Runnable> mRunnableCaptor;
    @Captor
    private ArgumentCaptor<ClipData> mClipDataCaptor;
    @Captor
    private ArgumentCaptor<String> mStringCaptor;

    private ClipboardListener mClipboardListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mClipboardOverlayControllerFactory.create(any())).thenReturn(
                mOverlayController);
        when(mClipboardManager.hasPrimaryClip()).thenReturn(true);
        Settings.Secure.putInt(
                mContext.getContentResolver(), SETTINGS_SECURE_USER_SETUP_COMPLETE, 1);

        mSampleClipData = new ClipData("Test", new String[]{"text/plain"},
                new ClipData.Item("Test Item"));
        when(mClipboardManager.getPrimaryClip()).thenReturn(mSampleClipData);
        when(mClipboardManager.getPrimaryClipSource()).thenReturn(mSampleSource);

        mDeviceConfigProxy = new DeviceConfigProxyFake();

        mClipboardListener = new ClipboardListener(getContext(), mDeviceConfigProxy,
                mClipboardOverlayControllerFactory, mClipboardManager,mClipboardToast,
                mUiEventLogger);
    }

    @Test
    public void test_disabled() {
        mDeviceConfigProxy.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_ENABLED,
                "false", false);
        mClipboardListener.start();
        verifyZeroInteractions(mClipboardManager);
        verifyZeroInteractions(mUiEventLogger);
    }

    @Test
    public void test_enabled() {
        mDeviceConfigProxy.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_ENABLED,
                "true", false);
        mClipboardListener.start();
        verify(mClipboardManager).addPrimaryClipChangedListener(any());
        verifyZeroInteractions(mUiEventLogger);
    }

    @Test
    public void test_consecutiveCopies() {
        mDeviceConfigProxy.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_ENABLED,
                "true", false);
        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mClipboardOverlayControllerFactory).create(any());

        verify(mOverlayController).setClipData(mClipDataCaptor.capture(), mStringCaptor.capture());

        assertEquals(mSampleClipData, mClipDataCaptor.getValue());
        assertEquals(mSampleSource, mStringCaptor.getValue());

        verify(mOverlayController).setOnSessionCompleteListener(mRunnableCaptor.capture());

        // Should clear the overlay controller
        mRunnableCaptor.getValue().run();

        mClipboardListener.onPrimaryClipChanged();

        verify(mClipboardOverlayControllerFactory, times(2)).create(any());

        // Not calling the runnable here, just change the clip again and verify that the overlay is
        // NOT recreated.

        mClipboardListener.onPrimaryClipChanged();

        verify(mClipboardOverlayControllerFactory, times(2)).create(any());
    }

    @Test
    public void test_shouldSuppressOverlay() {
        // Regardless of the package or emulator, nothing should be suppressed without the flag
        assertFalse(ClipboardListener.shouldSuppressOverlay(mSampleClipData, mSampleSource,
                false));
        assertFalse(ClipboardListener.shouldSuppressOverlay(mSampleClipData,
                ClipboardListener.SHELL_PACKAGE, false));
        assertFalse(ClipboardListener.shouldSuppressOverlay(mSampleClipData, mSampleSource,
                true));

        ClipDescription desc = new ClipDescription("Test", new String[]{"text/plain"});
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(ClipboardListener.EXTRA_SUPPRESS_OVERLAY, true);
        desc.setExtras(bundle);
        ClipData suppressableClipData = new ClipData(desc, new ClipData.Item("Test Item"));

        // Clip data with the suppression extra is only honored in the emulator or with the shell
        // package.
        assertFalse(ClipboardListener.shouldSuppressOverlay(suppressableClipData, mSampleSource,
                false));
        assertTrue(ClipboardListener.shouldSuppressOverlay(suppressableClipData, mSampleSource,
                true));
        assertTrue(ClipboardListener.shouldSuppressOverlay(suppressableClipData,
                ClipboardListener.SHELL_PACKAGE, false));
    }

    @Test
    public void test_logging_enterAndReenter() {
        mClipboardListener.start();

        mClipboardListener.onPrimaryClipChanged();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED, 0, mSampleSource);
        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_UPDATED, 0, mSampleSource);
    }

    @Test
    public void test_userSetupIncomplete_showsToast() {
        Settings.Secure.putInt(
                mContext.getContentResolver(), SETTINGS_SECURE_USER_SETUP_COMPLETE, 0);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_TOAST_SHOWN, 0, mSampleSource);
        verify(mClipboardToast, times(1)).showCopiedToast();
        verifyZeroInteractions(mClipboardOverlayControllerFactory);
    }
}
