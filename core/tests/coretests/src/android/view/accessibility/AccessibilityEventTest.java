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
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AccessibilityEvent is public, so CTS covers it pretty well. Verifying hidden methods here.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityEventTest {
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
    public void testWindowChanges_getSetWorkAcrossParceling() {
        final int windowChanges = AccessibilityEvent.WINDOWS_CHANGE_TITLE
                | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
                | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED;
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setWindowChanges(windowChanges);
        assertEquals(windowChanges, copyEventViaParcel(event).getWindowChanges());
    }

    private AccessibilityEvent copyEventViaParcel(AccessibilityEvent event) {
        Parcel parcel = Parcel.obtain();
        event.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        return AccessibilityEvent.CREATOR.createFromParcel(parcel);
    }
}
