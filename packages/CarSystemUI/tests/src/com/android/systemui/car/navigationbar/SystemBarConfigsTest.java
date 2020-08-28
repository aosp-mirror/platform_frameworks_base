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

package com.android.systemui.car.navigationbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SystemBarConfigsTest extends SysuiTestCase {
    private static final int SYSTEM_BAR_GIRTH = 100;

    private SystemBarConfigs mSystemBarConfigs;
    @Mock
    private Resources mResources;
    @Mock
    private CarNavigationBarView mCarNavigationBarView;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        setDefaultValidConfig();
    }

    @Test
    public void onInit_allSystemBarsEnabled_eachHasUniqueBarTypes_doesNotThrowException() {
        mSystemBarConfigs = new SystemBarConfigs(mResources);
    }

    @Test(expected = RuntimeException.class)
    public void onInit_allSystemBarsEnabled_twoBarsHaveDuplicateType_throwsRuntimeException() {
        when(mResources.getInteger(R.integer.config_topSystemBarType)).thenReturn(0);
        when(mResources.getInteger(R.integer.config_bottomSystemBarType)).thenReturn(0);

        mSystemBarConfigs = new SystemBarConfigs(mResources);
    }

    @Test
    public void onInit_allSystemBarsEnabled_systemBarSidesSortedByZOrder() {
        mSystemBarConfigs = new SystemBarConfigs(mResources);
        List<Integer> actualOrder = mSystemBarConfigs.getSystemBarSidesByZOrder();
        List<Integer> expectedOrder = new ArrayList<>();
        expectedOrder.add(SystemBarConfigs.LEFT);
        expectedOrder.add(SystemBarConfigs.RIGHT);
        expectedOrder.add(SystemBarConfigs.TOP);
        expectedOrder.add(SystemBarConfigs.BOTTOM);

        assertTrue(actualOrder.equals(expectedOrder));
    }

    @Test(expected = RuntimeException.class)
    public void onInit_intersectingBarsHaveSameZOrder_throwsRuntimeException() {
        when(mResources.getInteger(R.integer.config_topSystemBarZOrder)).thenReturn(33);
        when(mResources.getInteger(R.integer.config_leftSystemBarZOrder)).thenReturn(33);

        mSystemBarConfigs = new SystemBarConfigs(mResources);
    }

    @Test
    public void getTopSystemBarLayoutParams_topBarEnabled_returnsTopSystemBarLayoutParams() {
        mSystemBarConfigs = new SystemBarConfigs(mResources);
        WindowManager.LayoutParams lp = mSystemBarConfigs.getLayoutParamsBySide(
                SystemBarConfigs.TOP);

        assertNotNull(lp);
    }

    @Test
    public void getTopSystemBarLayoutParams_topBarNotEnabled_returnsNull() {
        when(mResources.getBoolean(R.bool.config_enableTopNavigationBar)).thenReturn(false);
        mSystemBarConfigs = new SystemBarConfigs(mResources);
        WindowManager.LayoutParams lp = mSystemBarConfigs.getLayoutParamsBySide(
                SystemBarConfigs.TOP);

        assertNull(lp);
    }

    @Test
    public void topSystemBarHasHigherZOrderThanHuns_topSystemBarIsNavigationBarPanelType() {
        when(mResources.getInteger(R.integer.config_topSystemBarZOrder)).thenReturn(
                SystemBarConfigs.getHunZOrder() + 1);
        mSystemBarConfigs = new SystemBarConfigs(mResources);
        WindowManager.LayoutParams lp = mSystemBarConfigs.getLayoutParamsBySide(
                SystemBarConfigs.TOP);

        assertEquals(lp.type, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL);
    }

    @Test
    public void topSystemBarHasLowerZOrderThanHuns_topSystemBarIsStatusBarAdditionalType() {
        when(mResources.getInteger(R.integer.config_topSystemBarZOrder)).thenReturn(
                SystemBarConfigs.getHunZOrder() - 1);
        mSystemBarConfigs = new SystemBarConfigs(mResources);
        WindowManager.LayoutParams lp = mSystemBarConfigs.getLayoutParamsBySide(
                SystemBarConfigs.TOP);

        assertEquals(lp.type, WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL);
    }

    @Test
    public void updateInsetPaddings_overlappingBarWithHigherZOrderDisappeared_removesInset() {
        mSystemBarConfigs = new SystemBarConfigs(mResources);
        CarNavigationBarView leftBar = new CarNavigationBarView(mContext, /* attrs= */ null);
        Map<Integer, Boolean> visibilities = new ArrayMap<>();
        visibilities.put(SystemBarConfigs.TOP, false);
        visibilities.put(SystemBarConfigs.BOTTOM, true);
        visibilities.put(SystemBarConfigs.LEFT, true);
        visibilities.put(SystemBarConfigs.RIGHT, true);

        mSystemBarConfigs.updateInsetPaddings(SystemBarConfigs.LEFT, visibilities);
        mSystemBarConfigs.insetSystemBar(SystemBarConfigs.LEFT, leftBar);

        assertEquals(0, leftBar.getPaddingTop());
    }

    @Test
    public void updateInsetPaddings_overlappingBarWithHigherZOrderReappeared_addsInset() {
        mSystemBarConfigs = new SystemBarConfigs(mResources);
        CarNavigationBarView leftBar = new CarNavigationBarView(mContext, /* attrs= */ null);
        Map<Integer, Boolean> visibilities = new ArrayMap<>();
        visibilities.put(SystemBarConfigs.TOP, false);
        visibilities.put(SystemBarConfigs.BOTTOM, true);
        visibilities.put(SystemBarConfigs.LEFT, true);
        visibilities.put(SystemBarConfigs.RIGHT, true);

        mSystemBarConfigs.updateInsetPaddings(SystemBarConfigs.LEFT, visibilities);
        mSystemBarConfigs.insetSystemBar(SystemBarConfigs.LEFT, leftBar);
        visibilities.put(SystemBarConfigs.TOP, true);
        mSystemBarConfigs.updateInsetPaddings(SystemBarConfigs.LEFT, visibilities);
        mSystemBarConfigs.insetSystemBar(SystemBarConfigs.LEFT, leftBar);

        assertEquals(SYSTEM_BAR_GIRTH, leftBar.getPaddingTop());
    }

    // Set valid config where all system bars are enabled.
    private void setDefaultValidConfig() {
        when(mResources.getBoolean(R.bool.config_enableTopNavigationBar)).thenReturn(true);
        when(mResources.getBoolean(R.bool.config_enableBottomNavigationBar)).thenReturn(true);
        when(mResources.getBoolean(R.bool.config_enableLeftNavigationBar)).thenReturn(true);
        when(mResources.getBoolean(R.bool.config_enableRightNavigationBar)).thenReturn(true);

        when(mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height)).thenReturn(SYSTEM_BAR_GIRTH);
        when(mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height)).thenReturn(SYSTEM_BAR_GIRTH);
        when(mResources.getDimensionPixelSize(R.dimen.car_left_navigation_bar_width)).thenReturn(
                SYSTEM_BAR_GIRTH);
        when(mResources.getDimensionPixelSize(R.dimen.car_right_navigation_bar_width)).thenReturn(
                SYSTEM_BAR_GIRTH);

        when(mResources.getInteger(R.integer.config_topSystemBarType)).thenReturn(0);
        when(mResources.getInteger(R.integer.config_bottomSystemBarType)).thenReturn(1);
        when(mResources.getInteger(R.integer.config_leftSystemBarType)).thenReturn(2);
        when(mResources.getInteger(R.integer.config_rightSystemBarType)).thenReturn(3);

        when(mResources.getInteger(R.integer.config_topSystemBarZOrder)).thenReturn(5);
        when(mResources.getInteger(R.integer.config_bottomSystemBarZOrder)).thenReturn(10);
        when(mResources.getInteger(R.integer.config_leftSystemBarZOrder)).thenReturn(2);
        when(mResources.getInteger(R.integer.config_rightSystemBarZOrder)).thenReturn(3);
    }
}
