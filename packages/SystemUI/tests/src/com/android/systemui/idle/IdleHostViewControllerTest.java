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

package com.android.systemui.idle;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.PowerManager;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class IdleHostViewControllerTest extends SysuiTestCase {
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private PowerManager mPowerManager;
    @Mock private IdleHostView mIdleHostView;
    @Mock private Resources mResources;
    @Mock private Provider<View> mViewProvider;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private AmbientLightModeMonitor mAmbientLightModeMonitor;

    private KeyguardStateController.Callback mKeyguardStateCallback;
    private StatusBarStateController.StateListener mStatusBarStateListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mResources.getBoolean(R.bool.config_enableIdleMode)).thenReturn(true);
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        final IdleHostViewController controller = new IdleHostViewController(mBroadcastDispatcher,
                mPowerManager, mIdleHostView, mResources, mViewProvider, mKeyguardStateController,
                mStatusBarStateController, mAmbientLightModeMonitor);
        controller.init();
        controller.onViewAttached();

        // Captures keyguard state controller callback.
        ArgumentCaptor<KeyguardStateController.Callback> keyguardStateCallbackCaptor =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
        verify(mKeyguardStateController).addCallback(keyguardStateCallbackCaptor.capture());
        mKeyguardStateCallback = keyguardStateCallbackCaptor.getValue();

        // Captures status bar state listener.
        ArgumentCaptor<StatusBarStateController.StateListener> statusBarStateListenerCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mStatusBarStateController).addCallback(statusBarStateListenerCaptor.capture());
        mStatusBarStateListener = statusBarStateListenerCaptor.getValue();
    }

    @Test
    public void testStartDozingWhenLowLight() {
        // Keyguard showing.
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // Regular ambient lighting.
        final AmbientLightModeMonitor.Callback lightMonitorCallback =
                captureAmbientLightModeMonitorCallback();
        lightMonitorCallback.onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT);

        // Verifies it doesn't go to sleep yet.
        verify(mPowerManager, never()).goToSleep(anyLong(), anyInt(), anyInt());

        // Ambient lighting becomes dim.
        lightMonitorCallback.onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);

        // Verifies it goes to sleep.
        verify(mPowerManager).goToSleep(anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testWakeUpWhenRegularLight() {
        // Keyguard showing.
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // In low light / dozing.
        final AmbientLightModeMonitor.Callback lightMonitorCallback =
                captureAmbientLightModeMonitorCallback();
        lightMonitorCallback.onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        mStatusBarStateListener.onDozingChanged(true /*isDozing*/);

        // Regular ambient lighting.
        lightMonitorCallback.onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT);

        // Verifies it wakes up from sleep.
        verify(mPowerManager).wakeUp(anyLong(), anyInt(), anyString());
    }

    // Captures [AmbientLightModeMonitor.Callback] assuming that the ambient light mode monitor
    // has been started.
    private AmbientLightModeMonitor.Callback captureAmbientLightModeMonitorCallback() {
        ArgumentCaptor<AmbientLightModeMonitor.Callback> captor =
                ArgumentCaptor.forClass(AmbientLightModeMonitor.Callback.class);
        verify(mAmbientLightModeMonitor).start(captor.capture());
        return captor.getValue();
    }
}
