/*
 * Copyright 2017 The Android Open Source Project
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

package android.view.accessibility;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.os.Parcel;
import android.view.Display;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * AccessibilityEvent is public, so CTS covers it pretty well. Verifying hidden methods here.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityEventTest {
    // The number of fields tested in the corresponding CTS AccessibilityEventTest:
    // See the CTS tests for AccessibilityRecord:
    // fullyPopulateAccessibilityEvent, assertEqualsAccessiblityEvent,
    // and assertAccessibilityEventCleared

    /** The number of properties of the {@link AccessibilityEvent} class. */
    private static final int A11Y_EVENT_NON_STATIC_FIELD_COUNT = 33;

    // The number of fields tested in the corresponding CTS AccessibilityRecordTest:
    // assertAccessibilityRecordCleared, fullyPopulateAccessibilityRecord,
    // and assertEqualAccessibilityRecord

    /** The number of properties of the {@link AccessibilityRecord} class. */
    private static final int A11Y_RECORD_NON_STATIC_FIELD_COUNT = 23;

    @Test
    public void testImportantForAccessibiity_getSetWorkAcrossParceling() {
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setImportantForAccessibility(true);
        assertTrue(copyEventViaParcel(event).isImportantForAccessibility());

        event.setImportantForAccessibility(false);
        assertFalse(copyEventViaParcel(event).isImportantForAccessibility());
    }

    @Test
    public void testSouceNodeId_getSetWorkAcrossParceling() {
        final long sourceNodeId = 0x1234567890ABCDEFL;
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setSourceNodeId(sourceNodeId);
        assertEquals(sourceNodeId, copyEventViaParcel(event).getSourceNodeId());
    }

    @Test
    public void testSourceDisplayId_getSetWorkAcrossParceling() {
        final int sourceDisplayId = Display.DEFAULT_DISPLAY;
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setDisplayId(sourceDisplayId);
        assertEquals(sourceDisplayId, copyEventViaParcel(event).getDisplayId());
    }

    @Test
    public void testWindowChanges_getSetWorkAcrossParceling() {
        final int windowChanges = AccessibilityEvent.WINDOWS_CHANGE_TITLE
                | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
                | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED;
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setWindowChanges(windowChanges);
        assertEquals(windowChanges, copyEventViaParcel(event).getWindowChanges());
    }

    @Test
    public void dontForgetToUpdateA11yRecordCtsParcelingTestWhenYouAddNewFields() {
        AccessibilityEventTest.assertNoNewNonStaticFieldsAdded(
                AccessibilityRecord.class, A11Y_RECORD_NON_STATIC_FIELD_COUNT);
    }

    @Test
    public void dontForgetToUpdateA11yEventCtsParcelingTestWhenYouAddNewFields() {
        AccessibilityEventTest.assertNoNewNonStaticFieldsAdded(
                AccessibilityEvent.class, A11Y_EVENT_NON_STATIC_FIELD_COUNT);
    }

    private AccessibilityEvent copyEventViaParcel(AccessibilityEvent event) {
        Parcel parcel = Parcel.obtain();
        event.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        return AccessibilityEvent.CREATOR.createFromParcel(parcel);
    }

    /**
     * Asserts that no new fields have been added, so we are testing marshaling
     * of all such.
     */
    static void assertNoNewNonStaticFieldsAdded(Class<?> clazz, int expectedCount) {
        int nonStaticFieldCount = 0;

        while (clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if ((field.getModifiers() & Modifier.STATIC) == 0) {
                    nonStaticFieldCount++;
                }
            }
            clazz = clazz.getSuperclass();
        }

        String message = "New fields have been added, so add code to test marshaling them.";
        assertEquals(message, expectedCount, nonStaticFieldCount);
    }
}
