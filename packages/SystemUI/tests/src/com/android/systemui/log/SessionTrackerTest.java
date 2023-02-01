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

package com.android.systemui.log;

import static android.app.StatusBarManager.ALL_SESSIONS;
import static android.app.StatusBarManager.SESSION_BIOMETRIC_PROMPT;
import static android.app.StatusBarManager.SESSION_KEYGUARD;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SessionTrackerTest extends SysuiTestCase {
    @Mock
    private IStatusBarService mStatusBarService;
    @Mock
    private AuthController mAuthController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardStateController mKeyguardStateController;

    @Captor
    ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardUpdateMonitorCallbackCaptor;
    KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback;

    @Captor
    ArgumentCaptor<KeyguardStateController.Callback> mKeyguardStateCallbackCaptor;
    KeyguardStateController.Callback mKeyguardStateCallback;

    @Captor
    ArgumentCaptor<AuthController.Callback> mAuthControllerCallbackCaptor;
    AuthController.Callback mAuthControllerCallback;

    private SessionTracker mSessionTracker;

    @Before
    public void setup() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mSessionTracker = new SessionTracker(
                mStatusBarService,
                mAuthController,
                mKeyguardUpdateMonitor,
                mKeyguardStateController
        );
    }

    @Test
    public void testOnStartShowingKeyguard() throws RemoteException {
        // GIVEN the keyguard is showing before start
        when(mKeyguardStateController.isShowing()).thenReturn(true);

        // WHEN started
        mSessionTracker.start();

        // THEN keyguard session has a session id
        assertNotNull(mSessionTracker.getSessionId(SESSION_KEYGUARD));

        // THEN send event to status bar service
        verify(mStatusBarService).onSessionStarted(eq(SESSION_KEYGUARD), any(InstanceId.class));
    }

    @Test
    public void testNoSessions() throws RemoteException {
        // GIVEN no sessions
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        // WHEN started
        mSessionTracker.start();

        // THEN all sessions are null
        for (int sessionType : ALL_SESSIONS) {
            assertNull(mSessionTracker.getSessionId(sessionType));
        }
    }

    @Test
    public void testBiometricPromptShowing() throws RemoteException {
        // GIVEN session tracker started w/o any sessions
        mSessionTracker.start();
        captureAuthControllerCallback();

        // WHEN auth controller shows the biometric prompt
        mAuthControllerCallback.onBiometricPromptShown();

        // THEN the biometric prompt session has a session id
        assertNotNull(mSessionTracker.getSessionId(SESSION_BIOMETRIC_PROMPT));

        // THEN session started event gets sent to status bar service
        verify(mStatusBarService).onSessionStarted(
                eq(SESSION_BIOMETRIC_PROMPT), any(InstanceId.class));
    }

    @Test
    public void testBiometricPromptDismissed() throws RemoteException {
        // GIVEN session tracker started w/o any sessions
        mSessionTracker.start();
        captureAuthControllerCallback();

        // WHEN auth controller shows the biometric prompt and then hides it
        mAuthControllerCallback.onBiometricPromptShown();
        mAuthControllerCallback.onBiometricPromptDismissed();

        // THEN the biometric prompt session no longer has a session id
        assertNull(mSessionTracker.getSessionId(SESSION_BIOMETRIC_PROMPT));

        // THEN session end event gets sent to status bar service
        verify(mStatusBarService).onSessionEnded(
                eq(SESSION_BIOMETRIC_PROMPT), any(InstanceId.class));
    }

    @Test
    public void testKeyguardSessionOnDeviceStartsSleeping() throws RemoteException {
        // GIVEN session tracker started w/o any sessions
        mSessionTracker.start();
        captureKeyguardUpdateMonitorCallback();

        // WHEN device starts going to sleep
        mKeyguardUpdateMonitorCallback.onStartedGoingToSleep(0);

        // THEN the keyguard session has a session id
        assertNotNull(mSessionTracker.getSessionId(SESSION_KEYGUARD));

        // THEN session start event gets sent to status bar service
        verify(mStatusBarService).onSessionStarted(
                eq(SESSION_KEYGUARD), any(InstanceId.class));
    }

    @Test
    public void testKeyguardSessionOnDeviceStartsSleepingTwiceInARow_startsNewKeyguardSession()
            throws RemoteException {
        // GIVEN session tracker started w/o any sessions
        mSessionTracker.start();
        captureKeyguardUpdateMonitorCallback();

        // WHEN device starts going to sleep
        mKeyguardUpdateMonitorCallback.onStartedGoingToSleep(0);

        // THEN the keyguard session has a session id
        final InstanceId firstSessionId = mSessionTracker.getSessionId(SESSION_KEYGUARD);
        assertNotNull(firstSessionId);

        // WHEN device starts going to sleep a second time
        mKeyguardUpdateMonitorCallback.onStartedGoingToSleep(0);

        // THEN there's a new keyguard session with a unique session id
        final InstanceId secondSessionId = mSessionTracker.getSessionId(SESSION_KEYGUARD);
        assertNotNull(secondSessionId);
        assertNotEquals(firstSessionId, secondSessionId);

        // THEN session start event gets sent to status bar service twice (once per going to
        // sleep signal)
        verify(mStatusBarService, times(2)).onSessionStarted(
                eq(SESSION_KEYGUARD), any(InstanceId.class));
    }

    @Test
    public void testKeyguardSessionOnKeyguardShowingChange() throws RemoteException {
        // GIVEN session tracker started w/o any sessions
        mSessionTracker.start();
        captureKeyguardStateControllerCallback();

        // WHEN keyguard becomes visible (ie: from lockdown)
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // THEN the keyguard session has a session id
        assertNotNull(mSessionTracker.getSessionId(SESSION_KEYGUARD));

        // THEN session start event gets sent to status bar service
        verify(mStatusBarService).onSessionStarted(
                eq(SESSION_KEYGUARD), any(InstanceId.class));
    }

    @Test
    public void testKeyguardSessionOnKeyguardNotShowing() throws RemoteException {
        // GIVEN session tracker started w/o any sessions
        mSessionTracker.start();
        captureKeyguardStateControllerCallback();

        // WHEN keyguard was showing and now it's not
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mKeyguardStateCallback.onKeyguardShowingChanged();
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        mKeyguardStateCallback.onKeyguardShowingChanged();

        // THEN the keyguard session no longer has a session id
        assertNull(mSessionTracker.getSessionId(SESSION_KEYGUARD));

        // THEN session end event gets sent to status bar service
        verify(mStatusBarService).onSessionEnded(
                eq(SESSION_KEYGUARD), any(InstanceId.class));
    }

    void captureKeyguardUpdateMonitorCallback() {
        verify(mKeyguardUpdateMonitor).registerCallback(
                mKeyguardUpdateMonitorCallbackCaptor.capture());
        mKeyguardUpdateMonitorCallback = mKeyguardUpdateMonitorCallbackCaptor.getValue();
    }

    void captureKeyguardStateControllerCallback() {
        verify(mKeyguardStateController).addCallback(
                mKeyguardStateCallbackCaptor.capture());
        mKeyguardStateCallback = mKeyguardStateCallbackCaptor.getValue();
    }

    void captureAuthControllerCallback() {
        verify(mAuthController).addCallback(
                mAuthControllerCallbackCaptor.capture());
        mAuthControllerCallback = mAuthControllerCallbackCaptor.getValue();
    }
}
