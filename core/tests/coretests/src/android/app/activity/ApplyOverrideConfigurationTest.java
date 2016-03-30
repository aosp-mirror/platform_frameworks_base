/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.app.activity;

import android.app.UiAutomation;
import android.content.res.Configuration;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.ActivityInstrumentationTestCase2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ApplyOverrideConfigurationTest extends
        ActivityInstrumentationTestCase2<ApplyOverrideConfigurationActivity> {

    public static final int OVERRIDE_WIDTH = 9999;

    public ApplyOverrideConfigurationTest() {
        super(ApplyOverrideConfigurationActivity.class);
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        getInstrumentation().getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);
    }

    @Test
    public void testConfigurationIsOverriden() throws Exception {
        Configuration config = getActivity().getResources().getConfiguration();
        assertEquals(OVERRIDE_WIDTH, config.smallestScreenWidthDp);

        getInstrumentation().getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_90);

        config = getActivity().getResources().getConfiguration();
        assertEquals(OVERRIDE_WIDTH, config.smallestScreenWidthDp);
    }

    @After
    @Override
    public void tearDown() throws Exception {
        getInstrumentation().getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE);
        super.tearDown();
    }
}
