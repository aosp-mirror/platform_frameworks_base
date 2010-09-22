/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.preference;

import android.preference.ListPreference;
import android.test.AndroidTestCase;

public class ListPreferenceTest extends AndroidTestCase {
    public void testListPreferenceSummaryFromEntries() {
        String[] entries = { "one", "two", "three" };
        String[] entryValues = { "1" , "2", "3" };
        ListPreference lp = new ListPreference(getContext());
        lp.setEntries(entries);
        lp.setEntryValues(entryValues);

        lp.setValue(entryValues[1]);
        assertTrue(lp.getSummary() == null);

        lp.setSummary("%1$s");
        assertEquals(entries[1], lp.getSummary());

        lp.setValue(entryValues[2]);
        assertEquals(entries[2], lp.getSummary());

        lp.setSummary(null);
        assertTrue(lp.getSummary() == null);

        lp.setSummary("The color is %1$s");
        assertEquals("The color is " + entries[2], lp.getSummary());
    }
}
