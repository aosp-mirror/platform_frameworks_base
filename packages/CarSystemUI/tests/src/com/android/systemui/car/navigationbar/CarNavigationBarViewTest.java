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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CarNavigationBarViewTest extends SysuiTestCase {

    private CarNavigationBarView mNavBarView;

    @Mock
    private CarNavigationBarController.NotificationsShadeController mNotificationsShadeController;

    @Mock
    private View.OnTouchListener mNavBarTouchListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @After
    public void tearDown() {
        getContext().getOrCreateTestableResources().addOverride(
                R.bool.config_consumeNavigationBarTouchWhenNotificationPanelOpen, false);
    }

    @Test
    public void dispatchTouch_shadeOpen_flagOff_doesNotConsumeTouch() {
        getContext().getOrCreateTestableResources().addOverride(
                R.bool.config_consumeNavigationBarTouchWhenNotificationPanelOpen, false);
        when(mNotificationsShadeController.isNotificationPanelOpen()).thenReturn(true);
        mNavBarView = (CarNavigationBarView) LayoutInflater.from(getContext()).inflate(
                R.layout.car_navigation_bar_view_test, /* root= */ null);
        mNavBarView.setNotificationsPanelController(mNotificationsShadeController);
        mNavBarView.setStatusBarWindowTouchListener(mNavBarTouchListener);

        boolean consume = mNavBarView.onInterceptTouchEvent(
                MotionEvent.obtain(/* downTime= */ 200, /* eventTime= */ 300,
                        MotionEvent.ACTION_MOVE, mNavBarView.getX(),
                        mNavBarView.getY(), /* metaState= */ 0));

        assertThat(consume).isFalse();
    }

    @Test
    public void dispatchTouch_shadeOpen_flagOn_consumesTouch() {
        getContext().getOrCreateTestableResources().addOverride(
                R.bool.config_consumeNavigationBarTouchWhenNotificationPanelOpen, true);
        when(mNotificationsShadeController.isNotificationPanelOpen()).thenReturn(true);
        mNavBarView = (CarNavigationBarView) LayoutInflater.from(getContext()).inflate(
                R.layout.car_navigation_bar_view_test, /* root= */ null);
        mNavBarView.setNotificationsPanelController(mNotificationsShadeController);
        mNavBarView.setStatusBarWindowTouchListener(mNavBarTouchListener);

        boolean consume = mNavBarView.onInterceptTouchEvent(
                MotionEvent.obtain(/* downTime= */ 200, /* eventTime= */ 300,
                        MotionEvent.ACTION_MOVE, mNavBarView.getX(),
                        mNavBarView.getY(), /* metaState= */ 0));

        assertThat(consume).isTrue();
    }
}
