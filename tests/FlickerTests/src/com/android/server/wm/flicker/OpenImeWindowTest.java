/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker;

import static com.android.server.wm.flicker.CommonTransitions.editTextSetFocus;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import com.android.server.wm.flicker.helpers.ImeAppHelper;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;

/**
 * Test IME window opening transitions.
 * To run this test: {@code atest FlickerTests:OpenImeWindowTest}
 */
@LargeTest
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class OpenImeWindowTest extends NonRotationTestBase {

    private static final String IME_WINDOW_TITLE = "InputMethod";

    public OpenImeWindowTest(String beginRotationName, int beginRotation) {
        super(beginRotationName, beginRotation);

        mTestApp = new ImeAppHelper(InstrumentationRegistry.getInstrumentation());
    }

    @Before
    public void runTransition() {
        run(editTextSetFocus((ImeAppHelper) mTestApp, mUiDevice, mBeginRotation)
                .includeJankyRuns().build());
    }

    @Test
    public void checkVisibility_imeWindowBecomesVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .hidesImeWindow(IME_WINDOW_TITLE)
                .then()
                .showsImeWindow(IME_WINDOW_TITLE)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_imeLayerBecomesVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .hidesLayer(IME_WINDOW_TITLE)
                .then()
                .showsLayer(IME_WINDOW_TITLE)
                .forAllEntries());
    }
}
