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

package com.android.systemui.car.keyguard;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.navigationbar.CarNavigationBarController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.car.window.SystemUIOverlayWindowController;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CarKeyguardViewControllerTest extends SysuiTestCase {

    private TestableCarKeyguardViewController mCarKeyguardViewController;
    private OverlayViewGlobalStateController mOverlayViewGlobalStateController;
    private ViewGroup mBaseLayout;

    @Mock
    private KeyguardBouncer mBouncer;
    @Mock
    private CarNavigationBarController mCarNavigationBarController;
    @Mock
    private SystemUIOverlayWindowController mSystemUIOverlayWindowController;
    @Mock
    private CarKeyguardViewController.OnKeyguardCancelClickedListener mCancelClickedListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mOverlayViewGlobalStateController = new OverlayViewGlobalStateController(
                mCarNavigationBarController, mSystemUIOverlayWindowController);
        mBaseLayout = (ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.sysui_overlay_window, /* root= */ null);
        when(mSystemUIOverlayWindowController.getBaseLayout()).thenReturn(mBaseLayout);

        mCarKeyguardViewController = new TestableCarKeyguardViewController(
                mContext,
                Handler.getMain(),
                mock(CarServiceProvider.class),
                mOverlayViewGlobalStateController,
                mock(KeyguardStateController.class),
                mock(KeyguardUpdateMonitor.class),
                mock(BiometricUnlockController.class),
                mock(ViewMediatorCallback.class),
                mock(CarNavigationBarController.class),
                mock(LockPatternUtils.class),
                mock(DismissCallbackRegistry.class),
                mock(FalsingManager.class),
                mock(KeyguardBypassController.class)
        );
    }

    @Test
    public void onShow_bouncerIsSecure_showsBouncerWithSecuritySelectionReset() {
        when(mBouncer.isSecure()).thenReturn(true);
        mCarKeyguardViewController.show(/* options= */ null);

        verify(mBouncer).show(/* resetSecuritySelection= */ true);
    }

    @Test
    public void onShow_bouncerIsSecure_keyguardIsVisible() {
        when(mBouncer.isSecure()).thenReturn(true);
        mCarKeyguardViewController.show(/* options= */ null);

        assertThat(mBaseLayout.findViewById(R.id.keyguard_container).getVisibility()).isEqualTo(
                View.VISIBLE);
    }

    @Test
    public void onShow_bouncerNotSecure_hidesBouncerAndDestroysTheView() {
        when(mBouncer.isSecure()).thenReturn(false);
        mCarKeyguardViewController.show(/* options= */ null);

        verify(mBouncer).hide(/* destroyView= */ true);
    }

    @Test
    public void onShow_bouncerNotSecure_keyguardIsNotVisible() {
        when(mBouncer.isSecure()).thenReturn(false);
        mCarKeyguardViewController.show(/* options= */ null);

        assertThat(mBaseLayout.findViewById(R.id.keyguard_container).getVisibility()).isEqualTo(
                View.GONE);
    }

    @Test
    public void onHide_keyguardShowing_hidesBouncerAndDestroysTheView() {
        when(mBouncer.isSecure()).thenReturn(true);
        mCarKeyguardViewController.show(/* options= */ null);
        mCarKeyguardViewController.hide(/* startTime= */ 0, /* fadeoutDelay= */ 0);

        verify(mBouncer).hide(/* destroyView= */ true);
    }

    @Test
    public void onHide_keyguardNotShown_doesNotHideOrDestroyBouncer() {
        mCarKeyguardViewController.hide(/* startTime= */ 0, /* fadeoutDelay= */ 0);

        verify(mBouncer, never()).hide(anyBoolean());
    }

    @Test
    public void onHide_KeyguardNotVisible() {
        when(mBouncer.isSecure()).thenReturn(true);
        mCarKeyguardViewController.show(/* options= */ null);
        mCarKeyguardViewController.hide(/* startTime= */ 0, /* fadeoutDelay= */ 0);

        assertThat(mBaseLayout.findViewById(R.id.keyguard_container).getVisibility()).isEqualTo(
                View.GONE);
    }

    @Test
    public void onCancelClicked_callsCancelClickedListener() {
        when(mBouncer.isSecure()).thenReturn(true);
        mCarKeyguardViewController.show(/* options= */ null);
        mCarKeyguardViewController.registerOnKeyguardCancelClickedListener(mCancelClickedListener);
        mCarKeyguardViewController.onCancelClicked();

        verify(mCancelClickedListener).onCancelClicked();
    }

    @Test
    public void onCancelClicked_hidesBouncerAndDestroysTheView() {
        when(mBouncer.isSecure()).thenReturn(true);
        mCarKeyguardViewController.show(/* options= */ null);
        mCarKeyguardViewController.registerOnKeyguardCancelClickedListener(mCancelClickedListener);
        mCarKeyguardViewController.onCancelClicked();

        verify(mBouncer).hide(/* destroyView= */ true);
    }

    private class TestableCarKeyguardViewController extends CarKeyguardViewController {

        TestableCarKeyguardViewController(Context context,
                Handler mainHandler,
                CarServiceProvider carServiceProvider,
                OverlayViewGlobalStateController overlayViewGlobalStateController,
                KeyguardStateController keyguardStateController,
                KeyguardUpdateMonitor keyguardUpdateMonitor,
                BiometricUnlockController biometricUnlockController,
                ViewMediatorCallback viewMediatorCallback,
                CarNavigationBarController carNavigationBarController,
                LockPatternUtils lockPatternUtils,
                DismissCallbackRegistry dismissCallbackRegistry,
                FalsingManager falsingManager,
                KeyguardBypassController keyguardBypassController) {
            super(context, mainHandler, carServiceProvider, overlayViewGlobalStateController,
                    keyguardStateController, keyguardUpdateMonitor, biometricUnlockController,
                    viewMediatorCallback, carNavigationBarController, lockPatternUtils,
                    dismissCallbackRegistry, falsingManager, keyguardBypassController);
        }

        @Override
        public void onFinishInflate() {
            super.onFinishInflate();
            setKeyguardBouncer(CarKeyguardViewControllerTest.this.mBouncer);
        }
    }

}
