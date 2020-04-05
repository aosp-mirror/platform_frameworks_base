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

package com.android.internal.policy;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.hardware.display.DisplayManagerGlobal;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;


import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests {@link DecorContext}.
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class DecorContextTest {
    private Context mContext;
    private static final int EXTERNAL_DISPLAY = DEFAULT_DISPLAY + 1;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void testDecorContextWithDefaultDisplay() {
        Display defaultDisplay = new Display(DisplayManagerGlobal.getInstance(), DEFAULT_DISPLAY,
                new DisplayInfo(), DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        DecorContext context = new DecorContext(mContext.getApplicationContext(),
                mContext.createDisplayContext(defaultDisplay));

        assertDecorContextDisplay(DEFAULT_DISPLAY, context);
    }

    @Test
    public void testDecorContextWithExternalDisplay() {
        Display display = new Display(DisplayManagerGlobal.getInstance(), EXTERNAL_DISPLAY,
                new DisplayInfo(), DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        DecorContext context = new DecorContext(mContext.getApplicationContext(),
                mContext.createDisplayContext(display));

        assertDecorContextDisplay(EXTERNAL_DISPLAY, context);
    }

    private static void assertDecorContextDisplay(int expectedDisplayId,
            DecorContext decorContext) {
        Display associatedDisplay = decorContext.getDisplay();
        assertEquals(expectedDisplayId, associatedDisplay.getDisplayId());
    }
}
