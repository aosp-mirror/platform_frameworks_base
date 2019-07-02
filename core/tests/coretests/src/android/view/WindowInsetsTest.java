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
import static android.view.WindowInsets.Type.topBar;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // TODO: Move this to CTS once API made public
    @Test
    public void typeMap() {
        Builder b = new WindowInsets.Builder();
        b.setInsets(sideBars(), Insets.of(0, 0, 0, 100));
        b.setInsets(ime(), Insets.of(0, 0, 0, 300));
        WindowInsets insets = b.build();
        assertEquals(300, insets.getSystemWindowInsets().bottom);
    }

    // TODO: Move this to CTS once API made public
    @Test
    public void compatInsets() {
        Builder b = new WindowInsets.Builder();
        b.setSystemWindowInsets(Insets.of(0, 50, 30, 10));
        WindowInsets insets = b.build();
        assertEquals(Insets.of(0, 50, 0, 0), insets.getInsets(topBar()));
        assertEquals(Insets.of(0, 0, 30, 10), insets.getInsets(sideBars()));
    }

    // TODO: Move this to CTS once API made public
    @Test
    public void visibility() {
        Builder b = new WindowInsets.Builder();
        b.setInsets(sideBars(), Insets.of(0, 0, 0, 100));
        b.setInsets(ime(), Insets.of(0, 0, 0, 300));
        b.setVisible(sideBars(), true);
        b.setVisible(ime(), true);
        WindowInsets insets = b.build();
        assertTrue(insets.isVisible(sideBars()));
        assertTrue(insets.isVisible(sideBars() | ime()));
        assertFalse(insets.isVisible(sideBars() | topBar()));
    }

    // TODO: Move this to CTS once API made public
    @Test
    public void consume_doesntChangeVisibility() {
        Builder b = new WindowInsets.Builder();
        b.setInsets(ime(), Insets.of(0, 0, 0, 300));
        b.setVisible(ime(), true);
        WindowInsets insets = b.build();
        insets = insets.consumeSystemWindowInsets();
        assertTrue(insets.isVisible(ime()));
    }
}
