/**
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view.accessibility;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * This class exercises the caching and recycling of {@link AccessibilityEvent}s.
 */
public class RecycleAccessibilityEventTest extends TestCase {

    private static final String CLASS_NAME = "foo.bar.baz.Test";
    private static final String PACKAGE_NAME = "foo.bar.baz";
    private static final String TEXT = "Some stuff";

    private static final String CONTENT_DESCRIPTION = "Content description";
    private static final int ITEM_COUNT = 10;
    private static final int CURRENT_ITEM_INDEX = 1;

    private static final int FROM_INDEX = 1;
    private static final int ADDED_COUNT = 2;
    private static final int REMOVED_COUNT = 1;

    /**
     * If an {@link AccessibilityEvent} is marshaled/unmarshaled correctly
     */
    @SmallTest
    public void testAccessibilityEventViewTextChangedType() {
        AccessibilityEvent first =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
        assertNotNull(first);

        first.setClassName(CLASS_NAME);
        first.setPackageName(PACKAGE_NAME);
        first.getText().add(TEXT);
        first.setFromIndex(FROM_INDEX);
        first.setAddedCount(ADDED_COUNT);
        first.setRemovedCount(REMOVED_COUNT);
        first.setChecked(true);
        first.setContentDescription(CONTENT_DESCRIPTION);
        first.setItemCount(ITEM_COUNT);
        first.setCurrentItemIndex(CURRENT_ITEM_INDEX);
        first.setEnabled(true);
        first.setPassword(true);

        first.recycle();

        assertNotNull(first);
        assertNull(first.getClassName());
        assertNull(first.getPackageName());
        assertEquals(0, first.getText().size());
        assertFalse(first.isChecked());
        assertNull(first.getContentDescription());
        assertEquals(-1, first.getItemCount());
        assertEquals(AccessibilityEvent.INVALID_POSITION, first.getCurrentItemIndex());
        assertFalse(first.isEnabled());
        assertFalse(first.isPassword());
        assertEquals(-1, first.getFromIndex());
        assertEquals(-1, first.getAddedCount());
        assertEquals(-1, first.getRemovedCount());

        // get another event from the pool (this must be the recycled first)
        AccessibilityEvent second = AccessibilityEvent.obtain();
        assertEquals(first, second);
    }
}
