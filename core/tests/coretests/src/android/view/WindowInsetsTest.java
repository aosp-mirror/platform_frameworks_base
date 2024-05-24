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

package android.view;

import static android.view.WindowInsets.Type.SIZE;
import static android.view.WindowInsets.Type.systemBars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowInsetsTest {

    @Test
    public void systemWindowInsets_afterConsuming_isConsumed() {
        assertTrue(new WindowInsets(WindowInsets.createCompatTypeMap(new Rect(1, 2, 3, 4)), null,
                null, false, 0, 0, null, null, null, null,
                WindowInsets.Type.systemBars(), false, null, null, 0, 0)
                .consumeSystemWindowInsets().isConsumed());
    }

    @Test
    public void multiNullConstructor_isConsumed() {
        assertTrue(new WindowInsets(null, null, null, false, 0, 0, null, null, null, null,
                WindowInsets.Type.systemBars(), false, null, null, 0, 0).isConsumed());
    }

    @Test
    public void singleNullConstructor_isConsumed() {
        assertTrue(new WindowInsets((Rect) null).isConsumed());
    }

    @Test
    public void compatInsets_layoutStable() {
        Insets[] insets = new Insets[SIZE];
        Insets[] maxInsets = new Insets[SIZE];
        boolean[] visible = new boolean[SIZE];
        WindowInsets.assignCompatInsets(maxInsets, new Rect(0, 10, 0, 0));
        WindowInsets.assignCompatInsets(insets, new Rect(0, 0, 0, 0));
        WindowInsets windowInsets = new WindowInsets(insets, maxInsets, visible, false, 0,
                0, null, null, null, DisplayShape.NONE, systemBars(),
                true /* compatIgnoreVisibility */, null, null, 0, 0);
        assertEquals(Insets.of(0, 10, 0, 0), windowInsets.getSystemWindowInsets());
    }
}
