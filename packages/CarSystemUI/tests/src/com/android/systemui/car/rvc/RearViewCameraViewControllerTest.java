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

package com.android.systemui.car.rvc;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.app.ActivityView;
import android.content.ComponentName;
import android.content.Intent;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.car.window.OverlayViewGlobalStateController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@CarSystemUiTest
@RunWith(MockitoJUnitRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class RearViewCameraViewControllerTest extends SysuiTestCase {
    private static final String TEST_ACTIVITY_NAME = "testPackage/testActivity";
    private RearViewCameraViewController mRearViewCameraViewController;

    @Mock
    private OverlayViewGlobalStateController mOverlayViewGlobalStateController;
    @Mock
    private ActivityView mMockActivityView;
    @Captor
    private ArgumentCaptor<Intent> mIntentCaptor;

    private void setUpRearViewCameraViewController(String testActivityName) {
        mContext.getOrCreateTestableResources().addOverride(
                R.string.config_rearViewCameraActivity, testActivityName);
        mRearViewCameraViewController = new RearViewCameraViewController(
                mContext.getOrCreateTestableResources().getResources(),
                mOverlayViewGlobalStateController);
        mRearViewCameraViewController.inflate((ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.sysui_overlay_window, /* root= */ null));
    }

    @Test
    public void testEmptyResourceDisablesController() {
        setUpRearViewCameraViewController("");

        assertThat(mRearViewCameraViewController.isEnabled()).isFalse();
    }

    @Test
    public void testNonEmptyResourceEnablesController() {
        setUpRearViewCameraViewController(TEST_ACTIVITY_NAME);

        assertThat(mRearViewCameraViewController.isEnabled()).isTrue();
    }

    @Test
    public void testShowInternal() {
        setUpRearViewCameraViewController(TEST_ACTIVITY_NAME);
        assertThat(mRearViewCameraViewController.isShown()).isFalse();
        assertThat(mRearViewCameraViewController.mActivityView).isNull();

        mRearViewCameraViewController.showInternal();

        assertThat(mRearViewCameraViewController.isShown()).isTrue();
        assertThat(mRearViewCameraViewController.mActivityView).isNotNull();
    }

    @Test
    public void testHideInternal() {
        setUpRearViewCameraViewController(TEST_ACTIVITY_NAME);
        assertThat(mRearViewCameraViewController.isShown()).isFalse();
        mRearViewCameraViewController.showInternal();
        assertThat(mRearViewCameraViewController.isShown()).isTrue();

        mRearViewCameraViewController.hideInternal();

        assertThat(mRearViewCameraViewController.isShown()).isFalse();
        assertThat(mRearViewCameraViewController.mActivityView).isNull();
    }

    @Test
    public void testOnActivityViewReady_fireIntent() {
        setUpRearViewCameraViewController(TEST_ACTIVITY_NAME);
        mRearViewCameraViewController.mActivityViewCallback.onActivityViewReady(mMockActivityView);

        verify(mMockActivityView).startActivity(mIntentCaptor.capture());
        ComponentName expectedComponent = ComponentName.unflattenFromString(TEST_ACTIVITY_NAME);
        assertThat(mIntentCaptor.getValue().getComponent()).isEqualTo(expectedComponent);
    }
}
