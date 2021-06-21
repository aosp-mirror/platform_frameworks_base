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
import android.content.pm.PackageManager;
import android.media.tv.TvInputService;
import android.media.tv.tuner.Descrambler;
import android.media.tv.tuner.Tuner;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.RequiredFeatureRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
// TODO: (b/174500129) add TEST_MAPPING on TunerTest when TunerService is ready
public class TunerTest {
    private static final String TAG = "MediaTunerTest";

    @Rule
    public RequiredFeatureRule featureRule = new RequiredFeatureRule(
            PackageManager.FEATURE_TUNER);

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
        Tuner tuner = new Tuner(mContext, null,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_BACKGROUND);
        assertNotNull(tuner);
    }

    @Test
    public void testOpenDescrambler() throws Exception {
        Tuner tuner = new Tuner(mContext, null,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_BACKGROUND);
        Descrambler descrambler = tuner.openDescrambler();
        assertNotNull(descrambler);
    }
}
