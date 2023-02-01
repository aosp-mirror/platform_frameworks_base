/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;

import android.os.UserHandle;
import android.testing.TestableContext;
import android.view.Display;

import com.android.compatibility.common.util.TestUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
/**
 * Tests for {@link MagnificationScaleProvider}.
 */
public class MagnificationScaleProviderTest {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY + 1;
    private static final int CURRENT_USER_ID = UserHandle.USER_SYSTEM;
    private static final int SECOND_USER_ID = CURRENT_USER_ID + 1;

    private static final float TEST_SCALE = 3;
    private static final float DEFAULT_SCALE =
            MagnificationScaleProvider.DEFAULT_MAGNIFICATION_SCALE;

    @Rule
    public final TestableContext mContext = new TestableContext(getInstrumentation().getContext());

    private MagnificationScaleProvider mScaleProvider;

    @Before
    public void setUp() {
        mScaleProvider = new MagnificationScaleProvider(mContext);
    }

    @Test
    public void putScaleOnDefaultDisplay_getExpectedValue() throws Exception {
        mScaleProvider.putScale(TEST_SCALE, Display.DEFAULT_DISPLAY);

        TestUtils.waitUntil("settings value is not changed",
                () -> Float.compare(mScaleProvider.getScale(Display.DEFAULT_DISPLAY),
                TEST_SCALE) == 0);
    }

    @Test
    public void putScaleOnTestDisplay_getExpectedValue() {
        mScaleProvider.putScale(TEST_SCALE, TEST_DISPLAY);

        assertEquals(TEST_SCALE, mScaleProvider.getScale(TEST_DISPLAY), 0);
    }

    @Test
    public void onUserChanged_putScale_fallbackToDefaultScale() {
        mScaleProvider.putScale(TEST_SCALE, TEST_DISPLAY);

        mScaleProvider.onUserChanged(SECOND_USER_ID);
        assertEquals(DEFAULT_SCALE, mScaleProvider.getScale(TEST_DISPLAY), 0);
    }

    @Test
    public void onUserRemoved_setScaleOnSecondUser_fallbackToDefaultScale() {
        mScaleProvider.onUserChanged(SECOND_USER_ID);
        mScaleProvider.putScale(TEST_SCALE, TEST_DISPLAY);
        mScaleProvider.onUserChanged(CURRENT_USER_ID);

        mScaleProvider.onUserRemoved(SECOND_USER_ID);
        // Assume the second user is created with the same id
        mScaleProvider.onUserChanged(SECOND_USER_ID);

        assertEquals(DEFAULT_SCALE, mScaleProvider.getScale(TEST_DISPLAY), 0);
    }

    @Test
    public void onTestDisplayRemoved_setScaleOnTestDisplay_fallbackToDefaultScale() {
        mScaleProvider.putScale(TEST_SCALE, TEST_DISPLAY);

        mScaleProvider.onDisplayRemoved(TEST_DISPLAY);

        assertEquals(DEFAULT_SCALE, mScaleProvider.getScale(TEST_DISPLAY), 0);
    }
}
