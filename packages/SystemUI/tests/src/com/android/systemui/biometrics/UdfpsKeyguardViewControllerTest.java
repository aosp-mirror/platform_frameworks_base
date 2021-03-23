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

package com.android.systemui.biometrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class UdfpsKeyguardViewControllerTest extends SysuiTestCase {
    // Dependencies
    @Mock
    private UdfpsKeyguardView mView;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private DumpManager mDumpManager;

    private UdfpsKeyguardViewController mController;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerCaptor;
    private StatusBarStateController.StateListener mParentStatusBarStateListener;
    private StatusBarStateController.StateListener mStatusBarStateListener;

    @Captor private ArgumentCaptor<StatusBar.ExpansionChangedListener> mExpansionListenerCaptor;
    private StatusBar.ExpansionChangedListener mExpansionListener;

    @Captor private ArgumentCaptor<StatusBarKeyguardViewManager.AlternateAuthInterceptor>
            mAltAuthInterceptorCaptor;
    private StatusBarKeyguardViewManager.AlternateAuthInterceptor mAltAuthInterceptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mController = new UdfpsKeyguardViewController(
                mView,
                mStatusBarStateController,
                mStatusBar,
                mStatusBarKeyguardViewManager,
                mDumpManager);
    }

    @Test
    public void testRegistersExpansionChangedListenerOnAttached() {
        mController.onViewAttached();
        captureExpansionListener();
    }

    @Test
    public void testRegistersStatusBarStateListenersOnAttached() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
    }

    @Test
    public void testViewControllerQueriesSBStateOnAttached() {
        mController.onViewAttached();
        verify(mStatusBarStateController, times(2)).getState();
        verify(mStatusBarStateController).getDozeAmount();

        final float dozeAmount = .88f;
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(dozeAmount);
        captureStatusBarStateListeners();

        mController.onViewAttached();
        verify(mView).setPauseAuth(true);
        verify(mView).onDozeAmountChanged(dozeAmount, dozeAmount);
    }

    @Test
    public void testListenersUnregisteredOnDetached() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
        captureExpansionListener();
        mController.onViewDetached();

        verify(mStatusBarStateController).removeCallback(mParentStatusBarStateListener);
        verify(mStatusBarStateController).removeCallback(mStatusBarStateListener);
        verify(mStatusBar).removeExpansionChangedListener(mExpansionListener);
    }

    @Test
    public void testDozeEventsSentToView() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        final float linear = .55f;
        final float eased = .65f;
        mStatusBarStateListener.onDozeAmountChanged(linear, eased);

        verify(mView).onDozeAmountChanged(linear, eased);
    }

    @Test
    public void testShouldNotPauseAuthOnKeyguard() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
        captureExpansionListener();

        sendStatusBarStateChanged(StatusBarState.KEYGUARD);

        assertFalse(mController.shouldPauseAuth());
    }

    @Test
    public void testShouldPauseAuthOnShadeLocked() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
        captureExpansionListener();

        sendStatusBarStateChanged(StatusBarState.SHADE_LOCKED);

        assertTrue(mController.shouldPauseAuth());
    }

    @Test
    public void testOverrideShouldPauseAuthOnShadeLocked() {
        mController.onViewAttached();
        captureStatusBarStateListeners();
        captureAltAuthInterceptor();

        sendStatusBarStateChanged(StatusBarState.SHADE_LOCKED);
        assertTrue(mController.shouldPauseAuth());

        mAltAuthInterceptor.showAlternativeAuthMethod(); // force show
        assertFalse(mController.shouldPauseAuth());
        assertTrue(mAltAuthInterceptor.isShowingAlternateAuth());

        mAltAuthInterceptor.resetForceShow(); // stop force show
        assertTrue(mController.shouldPauseAuth());
        assertFalse(mAltAuthInterceptor.isShowingAlternateAuth());
    }

    @Test
    public void testOnDetachedStateReset() {
        // GIVEN view is attached, alt auth is force being shown
        mController.onViewAttached();
        captureStatusBarStateListeners();
        captureAltAuthInterceptor();

        mAltAuthInterceptor.showAlternativeAuthMethod(); // alt auth force show

        // WHEN view is detached
        mController.onViewDetached();

        // THEN alt auth state reports not showing
        assertFalse(mAltAuthInterceptor.isShowingAlternateAuth());
    }

    private void sendStatusBarStateChanged(int statusBarState) {
        mStatusBarStateListener.onStateChanged(statusBarState);
        mParentStatusBarStateListener.onStateChanged(statusBarState);
    }

    private void captureStatusBarStateListeners() {
        verify(mStatusBarStateController, times(2)).addCallback(mStateListenerCaptor.capture());
        List<StatusBarStateController.StateListener> stateListeners =
                mStateListenerCaptor.getAllValues();
        mParentStatusBarStateListener = stateListeners.get(0);
        mStatusBarStateListener = stateListeners.get(1);
    }

    private void captureExpansionListener() {
        verify(mStatusBar).addExpansionChangedListener(mExpansionListenerCaptor.capture());
        mExpansionListener = mExpansionListenerCaptor.getValue();
    }

    private void captureAltAuthInterceptor() {
        verify(mStatusBarKeyguardViewManager).setAlternateAuthInterceptor(
                mAltAuthInterceptorCaptor.capture());
        mAltAuthInterceptor = mAltAuthInterceptorCaptor.getValue();
    }
}
