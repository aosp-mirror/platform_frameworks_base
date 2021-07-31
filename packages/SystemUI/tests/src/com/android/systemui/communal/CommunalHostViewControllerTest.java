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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.ref.WeakReference;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalHostViewControllerTest extends SysuiTestCase {
    @Mock
    private Context mContext;

    @Mock
    private KeyguardStateController mKeyguardStateController;

    @Mock
    private StatusBarStateController mStatusBarStateController;

    @Mock
    private CommunalHostView mCommunalView;

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private CommunalHostViewController mController;

    @Mock
    private CommunalSource mCommunalSource;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mCommunalView.isAttachedToWindow()).thenReturn(true);

        mController = new CommunalHostViewController(mFakeExecutor, mKeyguardStateController,
                mStatusBarStateController, mCommunalView);
        mController.init();
        mFakeExecutor.runAllReady();
    }

    @Test
    public void testShow() {
        ArgumentCaptor<KeyguardStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);

        // Capture callback value for later use.
        verify(mKeyguardStateController).addCallback(callbackCapture.capture());

        // Verify the communal view is shown when the controller is initialized with keyguard
        // showing (see setup).
        Mockito.clearInvocations(mCommunalView);
        mController.show(new WeakReference<>(mCommunalSource));
        mFakeExecutor.runAllReady();
        verify(mCommunalView).setVisibility(View.VISIBLE);

        // Trigger keyguard off to ensure visibility of communal view is changed accordingly.
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        callbackCapture.getValue().onKeyguardShowingChanged();
        mFakeExecutor.runAllReady();
        verify(mCommunalView).setVisibility(View.INVISIBLE);
    }
}
