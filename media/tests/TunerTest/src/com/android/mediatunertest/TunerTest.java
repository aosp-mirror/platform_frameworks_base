/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.mediatunertest;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.media.tv.tuner.Descrambler;
import android.media.tv.tuner.Tuner;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TunerTest {
    private static final String TAG = "MediaTunerTest";

    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testTunerConstructor() throws Exception {
        Tuner tuner = new Tuner(mContext, "123", 1);
        assertNotNull(tuner);
    }

    @Test
    public void testOpenDescrambler() throws Exception {
        Tuner tuner = new Tuner(mContext, "123", 1);
        Descrambler descrambler = tuner.openDescrambler();
        assertNotNull(descrambler);
    }
}
