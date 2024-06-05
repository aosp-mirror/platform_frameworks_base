/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 * atest RotaryInputGraphViewTest
 */
@RunWith(AndroidJUnit4.class)
public class RotaryInputGraphViewTest {

    private RotaryInputGraphView mRotaryInputGraphView;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getContext();

        mRotaryInputGraphView = new RotaryInputGraphView(context);
    }

    @Test
    public void startsWithDefaultFrameCenter() {
        assertEquals(0, mRotaryInputGraphView.getFrameCenterPosition(), 0.01);
    }

    @Test
    public void addValue_translatesRotaryInputGraphViewWithHighScrollValue() {
        final float scrollAxisValue = 1000f;
        final long eventTime = 0;

        mRotaryInputGraphView.addValue(scrollAxisValue, eventTime);

        assertTrue(mRotaryInputGraphView.getFrameCenterPosition() > 0);
    }
}
