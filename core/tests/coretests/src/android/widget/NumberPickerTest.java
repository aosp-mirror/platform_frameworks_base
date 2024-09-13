/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class NumberPickerTest {
    @Test
    public void testAccessibilityFocusedProperty() {
        final int virtualViewIdIncrement = 1;
        final int VirtualViewIdInput = 2;
        final int VirtualViewIdDecrement = 3;
        final NumberPicker np =
                new NumberPicker(InstrumentationRegistry.getInstrumentation().getContext());
        final AccessibilityNodeProvider provider = np.getAccessibilityNodeProvider();

        AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(View.NO_ID);
        assertFalse(info.isAccessibilityFocused());
        info.recycle();
        provider.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        info = provider.createAccessibilityNodeInfo(View.NO_ID);
        assertTrue(info.isAccessibilityFocused());
        info.recycle();

        info = provider.createAccessibilityNodeInfo(virtualViewIdIncrement);
        assertFalse(info.isAccessibilityFocused());
        info.recycle();
        provider.performAction(
                virtualViewIdIncrement,
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
        );
        info = provider.createAccessibilityNodeInfo(virtualViewIdIncrement);
        assertTrue(info.isAccessibilityFocused());
        info.recycle();

        info = provider.createAccessibilityNodeInfo(VirtualViewIdInput);
        assertFalse(info.isAccessibilityFocused());
        info.recycle();
        provider.performAction(
                VirtualViewIdInput,
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
        );
        info = provider.createAccessibilityNodeInfo(VirtualViewIdInput);
        assertTrue(info.isAccessibilityFocused());
        info.recycle();

        info = provider.createAccessibilityNodeInfo(VirtualViewIdDecrement);
        assertFalse(info.isAccessibilityFocused());
        info.recycle();
        provider.performAction(
                VirtualViewIdDecrement,
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
        );
        info = provider.createAccessibilityNodeInfo(VirtualViewIdDecrement);
        assertTrue(info.isAccessibilityFocused());
        info.recycle();
    }
}
