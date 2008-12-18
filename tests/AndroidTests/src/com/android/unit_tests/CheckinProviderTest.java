/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.unit_tests;

import org.apache.commons.codec.binary.Base64;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Checkin;
import android.server.checkin.CheckinProvider;
import android.server.data.BuildData;
import android.server.data.CrashData;
import android.server.data.ThrowableData;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;

/** Unit test for {@link CheckinProvider}. */
public class CheckinProviderTest extends AndroidTestCase {
    @MediumTest
    public void testEventReport() {
        long start = System.currentTimeMillis();
        ContentResolver r = getContext().getContentResolver();
        Checkin.logEvent(r, Checkin.Events.Tag.TEST, "Test Value");

        Cursor c = r.query(Checkin.Events.CONTENT_URI,
                null,
                Checkin.Events.TAG + "=?",
                new String[] { Checkin.Events.Tag.TEST.toString() },
                null);

        long id = -1;
        while (c.moveToNext()) {
            String tag = c.getString(c.getColumnIndex(Checkin.Events.TAG));
            String value = c.getString(c.getColumnIndex(Checkin.Events.VALUE));
            long date = c.getLong(c.getColumnIndex(Checkin.Events.DATE));
            assertEquals(Checkin.Events.Tag.TEST.toString(), tag);
            if ("Test Value".equals(value) && date >= start) {
                assertTrue(id < 0);
                id = c.getInt(c.getColumnIndex(Checkin.Events._ID));
            }
        }
        assertTrue(id > 0);

        int rows = r.delete(ContentUris.withAppendedId(Checkin.Events.CONTENT_URI, id), null, null);
        assertEquals(1, rows);
        c.requery();
        while (c.moveToNext()) {
            long date = c.getLong(c.getColumnIndex(Checkin.Events.DATE));
            assertTrue(date < start);  // Have deleted the only newer TEST.
        }

        c.close();
    }

    @MediumTest
    public void testStatsUpdate() {
        ContentResolver r = getContext().getContentResolver();

        // First, delete any existing data associated with the TEST tag.
        Uri uri = Checkin.updateStats(r, Checkin.Stats.Tag.TEST, 0, 0);
        assertNotNull(uri);
        assertEquals(1, r.delete(uri, null, null));
        assertFalse(r.query(uri, null, null, null, null).moveToNext());

        // Now, add a known quantity to the TEST tag.
        Uri u2 = Checkin.updateStats(r, Checkin.Stats.Tag.TEST, 1, 0.5);
        assertFalse(uri.equals(u2));

        Cursor c = r.query(u2, null, null, null, null);
        assertTrue(c.moveToNext());
        assertEquals(1, c.getInt(c.getColumnIndex(Checkin.Stats.COUNT)));
        assertEquals(0.5, c.getDouble(c.getColumnIndex(Checkin.Stats.SUM)));
        assertFalse(c.moveToNext());  // Only one.

        // Add another known quantity to TEST (should sum with the first).
        Uri u3 = Checkin.updateStats(r, Checkin.Stats.Tag.TEST, 2, 1.0);
        assertEquals(u2, u3);
        c.requery();
        assertTrue(c.moveToNext());
        assertEquals(3, c.getInt(c.getColumnIndex(Checkin.Stats.COUNT)));
        assertEquals(1.5, c.getDouble(c.getColumnIndex(Checkin.Stats.SUM)));
        assertFalse(c.moveToNext());  // Only one.

        // Now subtract the values; the whole row should disappear.
        Uri u4 = Checkin.updateStats(r, Checkin.Stats.Tag.TEST, -3, -1.5);
        assertNull(u4);
        c.requery();
        assertFalse(c.moveToNext());  // Row has been deleted.
        c.close();
    }

    @MediumTest
    public void testCrashReport() throws Exception {
        long start = System.currentTimeMillis();
        ContentResolver r = getContext().getContentResolver();

        // Log a test (fake) crash report.
        Checkin.reportCrash(r, new CrashData(
                "Test",
                "Test Activity",
                new BuildData("Test Build", "123", start),
                new ThrowableData(new RuntimeException("Test Exception"))));


        // Crashes aren't indexed; go through them all to find the one we added.
        Cursor c = r.query(Checkin.Crashes.CONTENT_URI, null, null, null, null);

        Uri uri = null;
        while (c.moveToNext()) {
            String coded = c.getString(c.getColumnIndex(Checkin.Crashes.DATA));
            byte[] bytes = Base64.decodeBase64(coded.getBytes());
            CrashData crash = new CrashData(
                    new DataInputStream(new ByteArrayInputStream(bytes)));

            // Should be exactly one recently added "Test" crash.
            if (crash.getId().equals("Test") && crash.getTime() > start) {
                assertEquals("Test Activity", crash.getActivity());
                assertEquals("Test Build", crash.getBuildData().getFingerprint());
                assertEquals("Test Exception",
                        crash.getThrowableData().getMessage());

                assertNull(uri);
                uri = ContentUris.withAppendedId(Checkin.Crashes.CONTENT_URI, c.getInt(c.getColumnIndex(Checkin.Crashes._ID)));
            }
        }
        assertNotNull(uri);
        c.close();

        // Update the "logs" column.
        ContentValues values = new ContentValues();
        values.put(Checkin.Crashes.LOGS, "Test Logs");
        assertEquals(1, r.update(uri, values, null, null));

        c = r.query(uri, null, null, null, null);
        assertTrue(c.moveToNext());
        String logs = c.getString(c.getColumnIndex(Checkin.Crashes.LOGS));
        assertEquals("Test Logs", logs);
        c.deleteRow();
        c.close();

        c.requery();
        assertFalse(c.moveToNext());
        c.close();
    }

    @MediumTest
    public void testPropertiesRestricted() throws Exception {
        ContentResolver r = getContext().getContentResolver();

        // The test app doesn't have the permission to access properties,
        // so any attempt to do so should fail.
        try {
            r.insert(Checkin.Properties.CONTENT_URI, new ContentValues());
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }

        try {
            r.query(Checkin.Properties.CONTENT_URI, null, null, null, null);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }
}
