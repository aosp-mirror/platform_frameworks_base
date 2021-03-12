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

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBar;

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

    private UdfpsKeyguardViewController mController;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerCaptor;
    private StatusBarStateController.StateListener mParentListener;
    private StatusBarStateController.StateListener mDozeListener;

    @Captor private ArgumentCaptor<StatusBar.ExpansionChangedListener> mExpansionListenerCaptor;
    private StatusBar.ExpansionChangedListener mExpansionListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mController = new UdfpsKeyguardViewController(
                mView,
                mStatusBarStateController,
                mStatusBar);
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
        verify(mStatusBarStateController).getState();
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

        verify(mStatusBarStateController).removeCallback(mParentListener);
        verify(mStatusBarStateController).removeCallback(mDozeListener);
        verify(mStatusBar).removeExpansionChangedListener(mExpansionListener);
    }

    @Test
    public void testDozeEventsSentToView() {
        mController.onViewAttached();
        captureStatusBarStateListeners();

        final float linear = .55f;
        final float eased = .65f;
        mDozeListener.onDozeAmountChanged(linear, eased);

        verify(mView).onDozeAmountChanged(linear, eased);
    }

    private void captureStatusBarStateListeners() {
        verify(mStatusBarStateController, times(2)).addCallback(mStateListenerCaptor.capture());
        List<StatusBarStateController.StateListener> stateListeners =
                mStateListenerCaptor.getAllValues();
        mParentListener = stateListeners.get(0);
        mDozeListener = stateListeners.get(1);
    }

    private void captureExpansionListener() {
        verify(mStatusBar).addExpansionChangedListener(mExpansionListenerCaptor.capture());
        mExpansionListener = mExpansionListenerCaptor.getValue();
    }
}
