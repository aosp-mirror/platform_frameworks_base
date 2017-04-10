/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive;

import com.android.ide.common.rendering.api.SessionParams;
import com.android.layoutlib.bridge.intensive.setup.ConfigGenerator;
import com.android.layoutlib.bridge.intensive.util.perf.PerformanceRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.annotation.NonNull;

import java.io.FileNotFoundException;

/**
 * Set of render tests
 */
@RunWith(PerformanceRunner.class)
public class PerformanceTests extends RenderTestBase {

    @Before
    public void setUp() {
        ignoreAllLogging();
    }


    private void render(@NonNull String layoutFileName)
            throws ClassNotFoundException, FileNotFoundException {
        SessionParams params = createSessionParams(layoutFileName, ConfigGenerator.NEXUS_5);
        render(params, 250);
    }

    @Test
    public void testActivity() throws ClassNotFoundException, FileNotFoundException {
        render("activity.xml");
    }

    @Test
    public void testAllWidgets() throws ClassNotFoundException, FileNotFoundException {
        render("allwidgets.xml");
    }
}
