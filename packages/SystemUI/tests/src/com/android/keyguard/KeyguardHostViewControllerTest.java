/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardHostViewControllerTest extends SysuiTestCase {
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private KeyguardHostView mKeyguardHostView;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private ViewMediatorCallback mViewMediatorCallback;
    @Mock
    KeyguardSecurityContainerController.Factory mKeyguardSecurityContainerControllerFactory;
    @Mock
    private KeyguardSecurityContainerController mKeyguardSecurityContainerController;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private TestableResources mTestableResources;
    private KeyguardHostViewController mKeyguardHostViewController;

    @Before
    public void setup() {
        mTestableResources = mContext.getOrCreateTestableResources();

        mKeyguardHostView = new KeyguardHostView(mContext);

        // Explicitly disable one handed keyguard.
        mTestableResources.addOverride(
                R.bool.can_use_one_handed_bouncer, false);

        when(mKeyguardSecurityContainerControllerFactory.create(any(
                KeyguardSecurityContainer.SecurityCallback.class)))
                .thenReturn(mKeyguardSecurityContainerController);
        mKeyguardHostViewController = new KeyguardHostViewController(
                mKeyguardHostView, mKeyguardUpdateMonitor, mAudioManager, mTelephonyManager,
                mViewMediatorCallback, mKeyguardSecurityContainerControllerFactory);
    }

    @Test
    public void testHasDismissActions() {
        assertFalse("Action not set yet", mKeyguardHostViewController.hasDismissActions());
        mKeyguardHostViewController.setOnDismissAction(mock(OnDismissAction.class),
                null /* cancelAction */);
        assertTrue("Action should exist", mKeyguardHostViewController.hasDismissActions());
    }

    @Test
    public void testOnStartingToHide() {
        mKeyguardHostViewController.onStartingToHide();
        verify(mKeyguardSecurityContainerController).onStartingToHide();
    }

    @Test
    public void testGravityReappliedOnConfigurationChange() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        mKeyguardHostView.setLayoutParams(lp);

        // Set initial gravity
        mTestableResources.addOverride(R.integer.keyguard_host_view_gravity,
                Gravity.CENTER);

        // Kick off the initial pass...
        mKeyguardHostViewController.init();
        assertEquals(
                ((FrameLayout.LayoutParams) mKeyguardHostView.getLayoutParams()).gravity,
                Gravity.CENTER);

        // Now simulate a config change
        mTestableResources.addOverride(R.integer.keyguard_host_view_gravity,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);

        mKeyguardHostViewController.updateResources();
        assertEquals(
                ((FrameLayout.LayoutParams) mKeyguardHostView.getLayoutParams()).gravity,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
    }

    @Test
    public void testGravityUsesOneHandGravityWhenApplicable() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        mKeyguardHostView.setLayoutParams(lp);

        mTestableResources.addOverride(
                R.integer.keyguard_host_view_gravity,
                Gravity.CENTER);
        mTestableResources.addOverride(
                R.integer.keyguard_host_view_one_handed_gravity,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);

        // Start disabled.
        mTestableResources.addOverride(
                R.bool.can_use_one_handed_bouncer, false);

        mKeyguardHostViewController.init();
        assertEquals(
                ((FrameLayout.LayoutParams) mKeyguardHostView.getLayoutParams()).gravity,
                Gravity.CENTER);

        // And enable
        mTestableResources.addOverride(
                R.bool.can_use_one_handed_bouncer, true);

        mKeyguardHostViewController.updateResources();
        assertEquals(
                ((FrameLayout.LayoutParams) mKeyguardHostView.getLayoutParams()).gravity,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
    }

    @Test
    public void testUpdateKeyguardPositionDelegatesToSecurityContainer() {
        mKeyguardHostViewController.updateKeyguardPosition(1.0f);

        verify(mKeyguardSecurityContainerController).updateKeyguardPosition(1.0f);
    }
}
