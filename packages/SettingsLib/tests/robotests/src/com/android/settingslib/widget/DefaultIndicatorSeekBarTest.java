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

package com.android.settingslib.widget;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DefaultIndicatorSeekBarTest {

    private DefaultIndicatorSeekBar mDefaultIndicatorSeekBar;

    @Before
    public void setUp() {
        mDefaultIndicatorSeekBar = new DefaultIndicatorSeekBar(RuntimeEnvironment.application);
        mDefaultIndicatorSeekBar.setMax(100);
    }

    @After
    public void tearDown() {
        mDefaultIndicatorSeekBar = null;
    }

    @Test
    public void defaultProgress_setSucceeds() {
        mDefaultIndicatorSeekBar.setDefaultProgress(40);
        assertEquals(40, mDefaultIndicatorSeekBar.getDefaultProgress());
    }
}
