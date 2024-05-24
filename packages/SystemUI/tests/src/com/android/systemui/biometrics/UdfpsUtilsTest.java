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

package com.android.systemui.biometrics;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams;
import com.android.systemui.shared.biometrics.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
@SmallTest
public class UdfpsUtilsTest extends SysuiTestCase {
    @Rule
    public final MockitoRule rule = MockitoJUnit.rule();
    private String[] mTouchHints;
    private UdfpsUtils mUdfpsUtils;

    @Before
    public void setUp() {
        Resources resources = mContext.getResources();
        mTouchHints = new String[]{
                resources.getString(R.string.udfps_accessibility_touch_hints_left),
                resources.getString(R.string.udfps_accessibility_touch_hints_down),
                resources.getString(R.string.udfps_accessibility_touch_hints_right),
                resources.getString(R.string.udfps_accessibility_touch_hints_up),
        };
        mUdfpsUtils = new UdfpsUtils();
    }

    @Test
    public void testTouchOutsideAreaNoRotation() {
        int rotation = Surface.ROTATION_0;
        // touch at 0 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[0]);
        // touch at 90 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, -1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[1]);
        // touch at 180 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        -1 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[2]);
        // touch at 270 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[3]);
    }


    @Test
    public void testTouchOutsideAreaRotation90Degrees() {
        int rotation = Surface.ROTATION_90;
        // touch at 0 degrees -> 90 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 0 /* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[1]);
        // touch at 90 degrees -> 180 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, -1 /* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[2]);
        // touch at 180 degrees -> 270 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        -1 /* touchX */, 0 /* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[3]);
        // touch at 270 degrees -> 0 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[0]);
    }


    @Test
    public void testTouchOutsideAreaRotation270Degrees() {
        int rotation = Surface.ROTATION_270;
        // touch at 0 degrees -> 270 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[3]);
        // touch at 90 degrees -> 0 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, -1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[0]);
        // touch at 180 degrees -> 90 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        -1 /* touchX */, 0/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[1]);
        // touch at 270 degrees -> 180 degrees
        assertThat(
                mUdfpsUtils.onTouchOutsideOfSensorArea(true, mContext,
                        0 /* touchX */, 1/* touchY */,
                        new UdfpsOverlayParams(new Rect(), new Rect(), 0, 0, 1f, rotation,
                                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
                )
        ).isEqualTo(mTouchHints[2]);
    }
}
