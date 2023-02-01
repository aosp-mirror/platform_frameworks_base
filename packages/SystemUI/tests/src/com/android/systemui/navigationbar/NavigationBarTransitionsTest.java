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

package com.android.systemui.navigationbar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.IWindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NavigationBarTransitionsTest extends SysuiTestCase {

    @Mock
    LightBarTransitionsController.Factory mLightBarTransitionsFactory;
    @Mock
    LightBarTransitionsController mLightBarTransitions;
    @Mock
    EdgeBackGestureHandler.Factory mEdgeBackGestureHandlerFactory;
    @Mock
    EdgeBackGestureHandler mEdgeBackGestureHandler;
    @Mock
    IWindowManager mIWindowManager;

    private NavigationBarTransitions mTransitions;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mEdgeBackGestureHandlerFactory.create(any(Context.class)))
                .thenReturn(mEdgeBackGestureHandler);
        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(OverviewProxyService.class);
        mDependency.injectMockDependency(StatusBarStateController.class);
        mDependency.injectMockDependency(KeyguardStateController.class);
        mDependency.injectMockDependency(NavigationBarController.class);
        mDependency.injectTestDependency(EdgeBackGestureHandler.Factory.class,
                mEdgeBackGestureHandlerFactory);
        doReturn(mContext)
                .when(mDependency.injectMockDependency(NavigationModeController.class))
                .getCurrentUserContext();

        when(mLightBarTransitionsFactory.create(any())).thenReturn(mLightBarTransitions);
        NavigationBarView navBar = spy(new NavigationBarView(mContext, null));
        when(navBar.getCurrentView()).thenReturn(navBar);
        when(navBar.findViewById(anyInt())).thenReturn(navBar);
        mTransitions = new NavigationBarTransitions(
                navBar, mIWindowManager, mLightBarTransitionsFactory);
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