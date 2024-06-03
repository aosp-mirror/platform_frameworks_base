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

package com.android.keyguard;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.power.data.repository.FakePowerRepository;
import com.android.systemui.power.domain.interactor.PowerInteractorFactory;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class KeyguardStatusViewControllerBaseTest extends SysuiTestCase {

    private KosmosJavaAdapter mKosmos;
    @Mock protected KeyguardStatusView mKeyguardStatusView;

    @Mock protected KeyguardSliceViewController mKeyguardSliceViewController;
    @Mock protected KeyguardClockSwitchController mKeyguardClockSwitchController;
    @Mock protected KeyguardStateController mKeyguardStateController;
    @Mock protected KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock protected ConfigurationController mConfigurationController;
    @Mock protected DozeParameters mDozeParameters;
    @Mock protected ScreenOffAnimationController mScreenOffAnimationController;
    @Mock protected KeyguardLogger mKeyguardLogger;
    @Mock protected KeyguardStatusViewController mControllerMock;
    @Mock protected ViewTreeObserver mViewTreeObserver;
    @Mock protected DumpManager mDumpManager;
    protected FakeKeyguardRepository mFakeKeyguardRepository;
    protected FakePowerRepository mFakePowerRepository;

    protected KeyguardStatusViewController mController;

    @Mock protected KeyguardClockSwitch mKeyguardClockSwitch;
    @Mock protected FrameLayout mMediaHostContainer;
    @Mock protected KeyguardStatusAreaView mKeyguardStatusAreaView;

    @Before
    public void setup() {
        mKosmos = new KosmosJavaAdapter(this);
        MockitoAnnotations.initMocks(this);

        KeyguardInteractorFactory.WithDependencies deps = KeyguardInteractorFactory.create();
        mFakeKeyguardRepository = deps.getRepository();
        mFakePowerRepository = new FakePowerRepository();

        mController = new KeyguardStatusViewController(
                mKeyguardStatusView,
                mKeyguardSliceViewController,
                mKeyguardClockSwitchController,
                mKeyguardStateController,
                mKeyguardUpdateMonitor,
                mConfigurationController,
                mDozeParameters,
                mScreenOffAnimationController,
                mKeyguardLogger,
                mKosmos.getInteractionJankMonitor(),
                deps.getKeyguardInteractor(),
                mDumpManager,
                PowerInteractorFactory.create(
                        mFakePowerRepository
                ).getPowerInteractor()) {
                    @Override
                    void setProperty(
                            AnimatableProperty property,
                            float value,
                            boolean animate) {
                        // Route into the mock version for verification
                        mControllerMock.setProperty(property, value, animate);
                    }
                };

        when(mKeyguardStatusView.getViewTreeObserver()).thenReturn(mViewTreeObserver);
        when(mKeyguardClockSwitchController.getView()).thenReturn(mKeyguardClockSwitch);
        when(mKeyguardStatusView.findViewById(R.id.keyguard_status_area))
                .thenReturn(mKeyguardStatusAreaView);
    }

    protected void givenViewAttached() {
        ArgumentCaptor<View.OnAttachStateChangeListener> captor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
        verify(mKeyguardStatusView, atLeast(1)).addOnAttachStateChangeListener(captor.capture());

        for (View.OnAttachStateChangeListener listener : captor.getAllValues()) {
            listener.onViewAttachedToWindow(mKeyguardStatusView);
        }
    }
}
