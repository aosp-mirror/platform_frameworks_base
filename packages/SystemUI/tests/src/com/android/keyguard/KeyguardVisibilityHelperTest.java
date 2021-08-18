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

package com.android.keyguard;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;
import android.view.ViewPropertyAnimator;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.CommunalStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class KeyguardVisibilityHelperTest extends SysuiTestCase {
    @Mock
    private CommunalStateController mCommunalStateController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    com.android.systemui.statusbar.phone.DozeParameters mDozeParameters;
    @Mock
    UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock
    ViewPropertyAnimator mViewPropertyAnimator;
    @Mock
    View mTargetView;

    private KeyguardVisibilityHelper mKeyguardVisibilityHelper;
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mTargetView.animate()).thenReturn(mViewPropertyAnimator);
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mTargetView,
                mCommunalStateController, mKeyguardStateController, mDozeParameters,
                mUnlockedScreenOffAnimationController, false, false);
    }

    @Test
    public void testHideOnCommunal() {
        // Verify view is hidden when communal is visible.
        when(mCommunalStateController.getCommunalViewShowing()).thenReturn(true);
        mKeyguardVisibilityHelper.setViewVisibility(StatusBarState.KEYGUARD, false,
                false, StatusBarState.KEYGUARD);
        verify(mTargetView).setVisibility(View.GONE);
        verify(mTargetView).setAlpha(1.0f);

        // Verify view is shown when communal is not visible.
        when(mCommunalStateController.getCommunalViewShowing()).thenReturn(false);
        mKeyguardVisibilityHelper.setViewVisibility(StatusBarState.KEYGUARD, false,
                false, StatusBarState.KEYGUARD);
        verify(mTargetView).setVisibility(View.VISIBLE);
    }

    @Test
    public void testVisibleOnCommunal() {
        when(mCommunalStateController.getCommunalViewShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        // Verify that helpers constructed with visibility on communal are not hidden when communal
        // is present.
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mTargetView,
                mCommunalStateController, mKeyguardStateController, mDozeParameters,
                mUnlockedScreenOffAnimationController, false, true);
        mKeyguardVisibilityHelper.setViewVisibility(StatusBarState.KEYGUARD, false,
                false, StatusBarState.KEYGUARD);
        verify(mTargetView).setVisibility(View.VISIBLE);
    }
}
