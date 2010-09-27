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

package android.pim;

import android.content.ContentValues;
import android.pim.ICalendar;
import android.pim.RecurrenceSet;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.provider.Calendar;
import junit.framework.TestCase;

/**
 * Test some pim.RecurrenceSet functionality.
 */
public class RecurrenceSetTest extends TestCase {

    // Test a recurrence
    @SmallTest
    public void testRecurrenceSet0() throws Exception {
        String recurrence = "DTSTART;TZID=America/New_York:20080221T070000\n"
                + "DTEND;TZID=America/New_York:20080221T190000\n"
                + "RRULE:FREQ=DAILY;UNTIL=20080222T000000Z\n"
                + "EXDATE:20080222T120000Z";
        verifyPopulateContentValues(recurrence, "FREQ=DAILY;UNTIL=20080222T000000Z", null,
                null, "20080222T120000Z", 1203595200000L, "America/New_York", "P43200S", 0);
    }

    // Test 1 day all-day event
    @SmallTest
    public void testRecurrenceSet1() throws Exception {
        String recurrence = "DTSTART;VALUE=DATE:20090821\nDTEND;VALUE=DATE:20090822\n"
                + "RRULE:FREQ=YEARLY;WKST=SU";
        verifyPopulateContentValues(recurrence, "FREQ=YEARLY;WKST=SU", null,
                null, null, 1250812800000L, "UTC", "P1D", 1);
    }

    // Test 2 day all-day event
    @SmallTest
    public void testRecurrenceSet2() throws Exception {
        String recurrence = "DTSTART;VALUE=DATE:20090821\nDTEND;VALUE=DATE:20090823\n"
                + "RRULE:FREQ=YEARLY;WKST=SU";
        verifyPopulateContentValues(recurrence, "FREQ=YEARLY;WKST=SU", null,
                null, null, 1250812800000L, "UTC",  "P2D", 1);
    }

    // run populateContentValues and verify the results
    private void verifyPopulateContentValues(String recurrence, String rrule, String rdate,
            String exrule, String exdate, long dtstart, String tzid, String duration, int allDay)
            throws ICalendar.FormatException {
        ICalendar.Component recurrenceComponent =
                new ICalendar.Component("DUMMY", null /* parent */);
        ICalendar.parseComponent(recurrenceComponent, recurrence);
        ContentValues values = new ContentValues();
        RecurrenceSet.populateContentValues(recurrenceComponent, values);
        Log.d("KS", "values " + values);

        assertEquals(rrule, values.get(android.provider.Calendar.Events.RRULE));
        assertEquals(rdate, values.get(android.provider.Calendar.Events.RDATE));
        assertEquals(exrule, values.get(android.provider.Calendar.Events.EXRULE));
        assertEquals(exdate, values.get(android.provider.Calendar.Events.EXDATE));
        assertEquals(dtstart, (long) values.getAsLong(Calendar.Events.DTSTART));
        assertEquals(tzid, values.get(android.provider.Calendar.Events.EVENT_TIMEZONE));
        assertEquals(duration, values.get(android.provider.Calendar.Events.DURATION));
        assertEquals(allDay,
                (int) values.getAsInteger(android.provider.Calendar.Events.ALL_DAY));
    }
}
