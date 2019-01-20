/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display;

import static com.android.server.display.AppSaturationController.TRANSLATION_VECTOR;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.os.UserHandle;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.ColorDisplayService.ColorTransformController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.ref.WeakReference;

@RunWith(AndroidJUnit4.class)
public class AppSaturationControllerTest {

    private static final String TEST_PACKAGE_NAME = "com.android.test";

    private int mUserId;
    private AppSaturationController mAppSaturationController;
    private float[] mMatrix;

    @Mock
    private ColorTransformController mColorTransformController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mUserId = ActivityManager.getCurrentUser();
        mAppSaturationController = new AppSaturationController();
        mMatrix = new float[9];
    }

    @After
    public void tearDown() {
        mAppSaturationController = null;
        mUserId = UserHandle.USER_NULL;
    }

    @Test
    public void addColorTransformController_appliesExistingSaturation() {
        final WeakReference<ColorTransformController> ref = new WeakReference<>(
                mColorTransformController);
        mAppSaturationController.setSaturationLevel(TEST_PACKAGE_NAME, mUserId, 30);
        mAppSaturationController.addColorTransformController(TEST_PACKAGE_NAME, mUserId, ref);
        AppSaturationController.computeGrayscaleTransformMatrix(.3f, mMatrix);
        verify(mColorTransformController).applyAppSaturation(eq(mMatrix), eq(TRANSLATION_VECTOR));
    }

    @Test
    public void setSaturationLevel_resetToDefault() {
        final WeakReference<ColorTransformController> ref = new WeakReference<>(
                mColorTransformController);
        mAppSaturationController.addColorTransformController(TEST_PACKAGE_NAME, mUserId, ref);
        verify(mColorTransformController, never())
                .applyAppSaturation(any(), eq(TRANSLATION_VECTOR));
        mAppSaturationController.setSaturationLevel(TEST_PACKAGE_NAME, mUserId, 30);
        AppSaturationController.computeGrayscaleTransformMatrix(.3f, mMatrix);
        verify(mColorTransformController, times(1))
                .applyAppSaturation(eq(mMatrix), eq(TRANSLATION_VECTOR));
        mAppSaturationController.setSaturationLevel(TEST_PACKAGE_NAME, mUserId, 100);
        AppSaturationController.computeGrayscaleTransformMatrix(1.0f, mMatrix);
        verify(mColorTransformController, times(2))
                .applyAppSaturation(eq(mMatrix), eq(TRANSLATION_VECTOR));
    }

    @Test
    public void setSaturationLevel_updateLevel() {
        final WeakReference<ColorTransformController> ref = new WeakReference<>(
                mColorTransformController);
        mAppSaturationController.addColorTransformController(TEST_PACKAGE_NAME, mUserId, ref);
        verify(mColorTransformController, never())
                .applyAppSaturation(any(), eq(TRANSLATION_VECTOR));
        mAppSaturationController.setSaturationLevel(TEST_PACKAGE_NAME, mUserId, 30);
        AppSaturationController.computeGrayscaleTransformMatrix(.3f, mMatrix);
        verify(mColorTransformController).applyAppSaturation(eq(mMatrix), eq(TRANSLATION_VECTOR));
        mAppSaturationController.setSaturationLevel(TEST_PACKAGE_NAME, mUserId, 70);
        AppSaturationController.computeGrayscaleTransformMatrix(.7f, mMatrix);
        verify(mColorTransformController, times(2))
                .applyAppSaturation(eq(mMatrix), eq(TRANSLATION_VECTOR));
        mAppSaturationController.setSaturationLevel(TEST_PACKAGE_NAME, mUserId, 100);
        AppSaturationController.computeGrayscaleTransformMatrix(1.0f, mMatrix);
        verify(mColorTransformController, times(3))
                .applyAppSaturation(eq(mMatrix), eq(TRANSLATION_VECTOR));
    }
}
