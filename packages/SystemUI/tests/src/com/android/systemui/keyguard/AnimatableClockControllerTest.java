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

package com.android.systemui.keyguard;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.AnimatableClockController;
import com.android.keyguard.AnimatableClockView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AnimatableClockControllerTest extends SysuiTestCase {
    @Mock
    private AnimatableClockView mClockView;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private BatteryController mBatteryController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private Resources mResources;

    private MockitoSession mStaticMockSession;
    private AnimatableClockController mAnimatableClockController;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<View.OnAttachStateChangeListener> mAttachCaptor =
            ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
    private View.OnAttachStateChangeListener mAttachListener;

    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateCaptor;
    private StatusBarStateController.StateListener mStatusBarStateCallback;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStaticMockSession = mockitoSession()
                .mockStatic(Utils.class)
                .strictness(Strictness.LENIENT) // it's ok if mocked classes aren't used
                .startMocking();
        when(Utils.getColorAttrDefaultColor(anyObject(), anyInt())).thenReturn(0);

        mAnimatableClockController = new AnimatableClockController(
                mClockView,
                mStatusBarStateController,
                mBroadcastDispatcher,
                mBatteryController,
                mKeyguardUpdateMonitor,
                mResources
        );
        mAnimatableClockController.init();
        captureAttachListener();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnAttachedUpdatesDozeStateToTrue() {
        // GIVEN dozing
        when(mStatusBarStateController.isDozing()).thenReturn(true);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(1f);

        // WHEN the clock view gets attached
        mAttachListener.onViewAttachedToWindow(mClockView);

        // THEN the clock controller updated its dozing state to true
        assertTrue(mAnimatableClockController.isDozing());
    }

    @Test
    public void testOnAttachedUpdatesDozeStateToFalse() {
        // GIVEN not dozing
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(0f);

        // WHEN the clock view gets attached
        mAttachListener.onViewAttachedToWindow(mClockView);

        // THEN the clock controller updated its dozing state to false
        assertFalse(mAnimatableClockController.isDozing());
    }

    private void captureAttachListener() {
        verify(mClockView).addOnAttachStateChangeListener(mAttachCaptor.capture());
        mAttachListener = mAttachCaptor.getValue();
    }
}
