/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.IWindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NavigationBarTransitionsTest extends SysuiTestCase {

    private NavigationBarTransitions mTransitions;

    @Before
    public void setup() {
        mDependency.injectMockDependency(IWindowManager.class);
        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(OverviewProxyService.class);
        mDependency.injectMockDependency(StatusBarStateController.class);
        mDependency.injectMockDependency(KeyguardStateController.class);
        doReturn(mContext)
                .when(mDependency.injectMockDependency(NavigationModeController.class))
                .getCurrentUserContext();

        NavigationBarView navBar = spy(new NavigationBarView(mContext, null));
        when(navBar.getCurrentView()).thenReturn(navBar);
        when(navBar.findViewById(anyInt())).thenReturn(navBar);
        mTransitions = new NavigationBarTransitions(navBar, mock(CommandQueue.class));
    }

    @Test
    public void setIsLightsOut_NoAutoDim() {
        mTransitions.setAutoDim(false);

        assertFalse(mTransitions.isLightsOut(BarTransitions.MODE_OPAQUE));

        assertTrue(mTransitions.isLightsOut(BarTransitions.MODE_LIGHTS_OUT));
    }

    @Test
    public void setIsLightsOut_AutoDim() {
        mTransitions.setAutoDim(true);

        assertTrue(mTransitions.isLightsOut(BarTransitions.MODE_OPAQUE));

        assertTrue(mTransitions.isLightsOut(BarTransitions.MODE_LIGHTS_OUT));
    }
}