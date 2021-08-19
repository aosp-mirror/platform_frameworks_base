/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.FakeSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;

import java.lang.ref.WeakReference;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CommunalSourceMonitorTest extends SysuiTestCase {
    private CommunalSourceMonitor mCommunalSourceMonitor;
    private FakeSettings mSecureSettings;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSecureSettings = new FakeSettings();
        mCommunalSourceMonitor = new CommunalSourceMonitor(
                Handler.createAsync(TestableLooper.get(this).getLooper()), mSecureSettings);
    }

    @Test
    public void testSourceAddedBeforeCallbackAdded() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.setSource(source);
        mCommunalSourceMonitor.addCallback(callback);

        verifyOnSourceAvailableCalledWith(callback, source);
    }

    @Test
    public void testRemoveCallback() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        mCommunalSourceMonitor.removeCallback(callback);
        mCommunalSourceMonitor.setSource(source);

        verify(callback, never()).onSourceAvailable(any());
    }

    @Test
    public void testAddCallbackWithDefaultSetting() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        mCommunalSourceMonitor.setSource(source);

        verifyOnSourceAvailableCalledWith(callback, source);
    }

    @Test
    public void testAddCallbackWithSettingDisabled() {
        setCommunalEnabled(false);

        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        mCommunalSourceMonitor.setSource(source);

        verify(callback, never()).onSourceAvailable(any());
    }

    @Test
    public void testSettingEnabledAfterCallbackAdded() {
        setCommunalEnabled(false);

        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        mCommunalSourceMonitor.setSource(source);

        // The callback should not have executed since communal is disabled.
        verify(callback, never()).onSourceAvailable(any());

        // The callback should execute when the user changes the setting to enabled.
        setCommunalEnabled(true);
        verifyOnSourceAvailableCalledWith(callback, source);
    }

    @Test
    public void testSettingDisabledAfterCallbackAdded() {
        final CommunalSourceMonitor.Callback callback = mock(CommunalSourceMonitor.Callback.class);
        final CommunalSource source = mock(CommunalSource.class);

        mCommunalSourceMonitor.addCallback(callback);
        mCommunalSourceMonitor.setSource(source);
        verifyOnSourceAvailableCalledWith(callback, source);

        // The callback should execute again when the setting is disabled, with a value of null.
        setCommunalEnabled(false);
        verify(callback).onSourceAvailable(null);
    }

    private void setCommunalEnabled(boolean enabled) {
        mSecureSettings.putInt(Settings.Secure.COMMUNAL_MODE_ENABLED, enabled ? 1 : 0);
        TestableLooper.get(this).processAllMessages();
    }

    private void verifyOnSourceAvailableCalledWith(CommunalSourceMonitor.Callback callback,
            CommunalSource source) {
        final ArgumentCaptor<WeakReference<CommunalSource>> sourceCapture =
                ArgumentCaptor.forClass(WeakReference.class);
        verify(callback).onSourceAvailable(sourceCapture.capture());
        assertThat(sourceCapture.getValue().get()).isEqualTo(source);
    }
}
