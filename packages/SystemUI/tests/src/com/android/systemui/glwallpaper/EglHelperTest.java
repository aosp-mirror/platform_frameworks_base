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

package com.android.systemui.glwallpaper;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.PixelFormat;
import android.testing.AndroidTestingRunner;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceSession;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@Ignore
public class EglHelperTest extends SysuiTestCase {

    @Spy
    private EglHelper mEglHelper;

    @Mock
    private SurfaceHolder mSurfaceHolder;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        prepareSurface();
    }

    @After
    public void tearDown() {
        mSurfaceHolder.getSurface().destroy();
        mSurfaceHolder = null;
    }

    private void prepareSurface() {
        final SurfaceSession session = new SurfaceSession();
        final SurfaceControl control = new SurfaceControl.Builder(session)
                .setName("Test")
                .setBufferSize(100, 100)
                .setFormat(PixelFormat.RGB_888)
                .build();
        final Surface surface = new Surface();
        surface.copyFrom(control);
        when(mSurfaceHolder.getSurface()).thenReturn(surface);
        assertThat(mSurfaceHolder.getSurface()).isNotNull();
        assertThat(mSurfaceHolder.getSurface().isValid()).isTrue();
    }

    @Test
    public void testInit_normal() {
        mEglHelper.init(mSurfaceHolder, false /* wideColorGamut */);
        assertThat(mEglHelper.hasEglDisplay()).isTrue();
        assertThat(mEglHelper.hasEglContext()).isTrue();
        assertThat(mEglHelper.hasEglSurface()).isTrue();
        verify(mEglHelper).askCreatingEglWindowSurface(
                any(SurfaceHolder.class), eq(null), anyInt());
    }

    @Test
    public void testInit_wide_gamut() {
        // In EglHelper, EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490;
        doReturn(0x3490).when(mEglHelper).getWcgCapability();
        // In EglHelper, KHR_GL_COLOR_SPACE = "EGL_KHR_gl_colorspace";
        doReturn(true).when(mEglHelper).checkExtensionCapability("EGL_KHR_gl_colorspace");
        ArgumentCaptor<int[]> ac = ArgumentCaptor.forClass(int[].class);
        // {EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT, EGL_NONE}
        final int[] expectedArgument = new int[] {0x309D, 0x3490, 0x3038};

        mEglHelper.init(mSurfaceHolder, true /* wideColorGamut */);
        verify(mEglHelper)
                .askCreatingEglWindowSurface(any(SurfaceHolder.class), ac.capture(), anyInt());
        assertThat(ac.getValue()).isNotNull();
        assertThat(ac.getValue()).isEqualTo(expectedArgument);
    }

    @Test
    @Ignore
    public void testFinish_shouldNotCrash() {
        mEglHelper.terminateEglDisplay();
        assertThat(mEglHelper.hasEglDisplay()).isFalse();
        assertThat(mEglHelper.hasEglSurface()).isFalse();
        assertThat(mEglHelper.hasEglContext()).isFalse();

        mEglHelper.finish();
        verify(mEglHelper, never()).destroyEglContext();
        verify(mEglHelper, never()).destroyEglSurface();
        verify(mEglHelper, atMost(1)).terminateEglDisplay();
    }
}
