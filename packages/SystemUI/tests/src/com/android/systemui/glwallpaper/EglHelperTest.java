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

import static org.junit.Assert.*;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceHolder;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;


@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class EglHelperTest extends SysuiTestCase {

    @Mock
    private EglHelper mEglHelper;
    @Mock
    private SurfaceHolder mSurfaceHolder;

    @Before
    public void setUp() throws Exception {
        mEglHelper = mock(EglHelper.class, RETURNS_DEFAULTS);
        mSurfaceHolder = mock(SurfaceHolder.class, RETURNS_DEFAULTS);
    }

    @Test
    public void testInit_finish() {
        mEglHelper.init(mSurfaceHolder);
        mEglHelper.finish();
    }

    @Test
    public void testFinish_shouldNotCrash() {
        assertFalse(mEglHelper.hasEglDisplay());
        assertFalse(mEglHelper.hasEglSurface());
        assertFalse(mEglHelper.hasEglContext());

        mEglHelper.finish();
    }
}
