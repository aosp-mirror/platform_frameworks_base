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

package com.android.server.display;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the {@link DisplayDevice} class.
 *
 * Build/Install/Run:
 * atest DisplayServicesTests:DisplayDeviceTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DisplayDeviceTest {
    private final DisplayDeviceInfo mDisplayDeviceInfo = new DisplayDeviceInfo();
    private static final int WIDTH = 500;
    private static final int HEIGHT = 900;
    private static final Point PORTRAIT_SIZE = new Point(WIDTH, HEIGHT);
    private static final Point LANDSCAPE_SIZE = new Point(HEIGHT, WIDTH);

    @Mock
    private SurfaceControl.Transaction mMockTransaction;

    @Mock
    private DisplayAdapter mMockDisplayAdapter;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDisplayDeviceInfo.width = WIDTH;
        mDisplayDeviceInfo.height = HEIGHT;
        mDisplayDeviceInfo.rotation = ROTATION_0;
    }

    @Test
    public void testGetDisplaySurfaceDefaultSizeLocked_notRotated() {
        DisplayDevice displayDevice = new FakeDisplayDevice(mDisplayDeviceInfo,
                mMockDisplayAdapter);
        assertThat(displayDevice.getDisplaySurfaceDefaultSizeLocked()).isEqualTo(PORTRAIT_SIZE);
    }

    @Test
    public void testGetDisplaySurfaceDefaultSizeLocked_rotation0() {
        DisplayDevice displayDevice = new FakeDisplayDevice(mDisplayDeviceInfo,
                mMockDisplayAdapter);
        displayDevice.setProjectionLocked(mMockTransaction, ROTATION_0, new Rect(), new Rect());
        assertThat(displayDevice.getDisplaySurfaceDefaultSizeLocked()).isEqualTo(PORTRAIT_SIZE);
    }

    @Test
    public void testGetDisplaySurfaceDefaultSizeLocked_rotation90() {
        DisplayDevice displayDevice = new FakeDisplayDevice(mDisplayDeviceInfo,
                mMockDisplayAdapter);
        displayDevice.setProjectionLocked(mMockTransaction, ROTATION_90, new Rect(), new Rect());
        assertThat(displayDevice.getDisplaySurfaceDefaultSizeLocked()).isEqualTo(LANDSCAPE_SIZE);
    }

    @Test
    public void testGetDisplaySurfaceDefaultSizeLocked_rotation180() {
        DisplayDevice displayDevice = new FakeDisplayDevice(mDisplayDeviceInfo,
                mMockDisplayAdapter);
        displayDevice.setProjectionLocked(mMockTransaction, ROTATION_180, new Rect(), new Rect());
        assertThat(displayDevice.getDisplaySurfaceDefaultSizeLocked()).isEqualTo(PORTRAIT_SIZE);
    }

    @Test
    public void testGetDisplaySurfaceDefaultSizeLocked_rotation270() {
        DisplayDevice displayDevice = new FakeDisplayDevice(mDisplayDeviceInfo,
                mMockDisplayAdapter);
        displayDevice.setProjectionLocked(mMockTransaction, ROTATION_270, new Rect(), new Rect());
        assertThat(displayDevice.getDisplaySurfaceDefaultSizeLocked()).isEqualTo(LANDSCAPE_SIZE);
    }

    private static class FakeDisplayDevice extends DisplayDevice {
        private final DisplayDeviceInfo mDisplayDeviceInfo;

        FakeDisplayDevice(DisplayDeviceInfo displayDeviceInfo, DisplayAdapter displayAdapter) {
            super(displayAdapter, /* displayToken= */ null, /* uniqueId= */ "",
                    InstrumentationRegistry.getInstrumentation().getContext());
            mDisplayDeviceInfo = displayDeviceInfo;
        }

        @Override
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            return mDisplayDeviceInfo;
        }
    }
}
