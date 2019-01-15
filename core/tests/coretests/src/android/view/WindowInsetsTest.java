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

import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.sideBars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsets.Builder;

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
        assertTrue(new WindowInsets(new Rect(1, 2, 3, 4), null, false, false, null)
                .consumeSystemWindowInsets().isConsumed());
    }

    @Test
    public void multiNullConstructor_isConsumed() {
        assertTrue(new WindowInsets((Rect) null, null, false, false, null).isConsumed());
    }

    @Test
    public void singleNullConstructor_isConsumed() {
        assertTrue(new WindowInsets((Rect) null).isConsumed());
    }

    @Test
    public void typeMap() {
        Builder b = new WindowInsets.Builder();
        b.setInsets(sideBars(), Insets.of(0, 0, 0, 100));
        b.setInsets(ime(), Insets.of(0, 0, 0, 300));
        WindowInsets insets = b.build();
        assertEquals(300, insets.getSystemWindowInsets().bottom);
    }
}
