/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.udfps;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Rect;
import android.view.Surface;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;


@RunWith(RobolectricTestRunner.class)
public class UdfpsUtilsTest {
    @Rule
    public final MockitoRule rule = MockitoJUnit.rule();

    private Context mContext;
    private String[] mTouchHints;
    private UdfpsUtils mUdfpsUtils;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mTouchHints = mContext.getResources().getStringArray(
                R.array.udfps_accessibility_touch_hints);
        mUdfpsUtils = new UdfpsUtils();
    }

    @Test
    public void testTouchOutsideAreaNoRotation() {
        int rotation = Surface.ROTATION_0;
        // touch at 0 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[0]);
        // touch at 90 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, -1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[1]);
        // touch at 180 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        -1 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[2]);
        // touch at 270 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[3]);
    }


    @Test
    public void testTouchOutsideAreaNoRotation90Degrees() {
        int rotation = Surface.ROTATION_90;
        // touch at 0 degrees -> 90 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 0 /* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[1]);
        // touch at 90 degrees -> 180 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, -1 /* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[2]);
        // touch at 180 degrees -> 270 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        -1 /* touchX */, 0 /* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[3]);
        // touch at 270 degrees -> 0 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[0]);
    }


    @Test
    public void testTouchOutsideAreaNoRotation270Degrees() {
        int rotation = Surface.ROTATION_270;
        // touch at 0 degrees -> 270 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[3]);
        // touch at 90 degrees -> 0 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, -1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[0]);
        // touch at 180 degrees -> 90 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        -1 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[1]);
        // touch at 270 degrees -> 180 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation)
                )
        ).isEqualTo(mTouchHints[2]);
    }
}
