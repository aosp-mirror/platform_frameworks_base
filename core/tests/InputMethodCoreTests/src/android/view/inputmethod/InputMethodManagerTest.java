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

package android.view.inputmethod;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.hardware.display.DisplayManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodManagerTest {
    @Test
    public void testPrivateApiGetInstance() throws Exception {
        final InputMethodManager globalImm = InputMethodManager.getInstance();
        assertNotNull("InputMethodManager.getInstance() still needs to work due to"
                + " @UnsupportedAppUsage", globalImm);
        assertEquals("InputMethodManager.peekInstance() still needs to work due to"
                + " @UnsupportedAppUsage", globalImm, InputMethodManager.peekInstance());

        final Context testContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();

        final DisplayManager dm = testContext.getSystemService(DisplayManager.class);
        final Context defaultDisplayContext =
                testContext.createDisplayContext(dm.getDisplay(DEFAULT_DISPLAY));
        final InputMethodManager imm =
                defaultDisplayContext.getSystemService(InputMethodManager.class);
        assertEquals("InputMethodManager.getInstance() always returns the instance for the default"
                + " display.", globalImm, imm);
    }
}
