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

package android.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RedactingCursorTest {
    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Test
    public void testMissing() throws Exception {
        final Cursor redacting = getContext().getContentResolver().query(
                Uri.parse("content://android.content.RedactingProvider/missing"), null, null, null);

        redacting.moveToNext();
        assertEquals("foo", redacting.getString(0));
        assertEquals(10, redacting.getInt(1));
        assertEquals("/path/to/foo", redacting.getString(2));
        redacting.moveToNext();
        assertEquals("bar", redacting.getString(0));
        assertEquals(20, redacting.getInt(1));
        assertEquals("/path/to/bar", redacting.getString(2));
    }

    @Test
    public void testSingle() throws Exception {
        final Cursor redacting = getContext().getContentResolver().query(
                Uri.parse("content://android.content.RedactingProvider/single"), null, null, null);

        redacting.moveToNext();
        assertEquals(null, redacting.getString(0));
        assertEquals(10, redacting.getInt(1));
        assertEquals("/dev/null", redacting.getString(2));
        assertEquals(Cursor.FIELD_TYPE_NULL, redacting.getType(0));
        assertEquals(Cursor.FIELD_TYPE_INTEGER, redacting.getType(1));
        assertEquals(Cursor.FIELD_TYPE_STRING, redacting.getType(2));
        assertTrue(redacting.isNull(0));
        assertFalse(redacting.isNull(1));
        assertFalse(redacting.isNull(2));

        redacting.moveToNext();
        assertEquals(null, redacting.getString(0));
        assertEquals(20, redacting.getInt(1));
        assertEquals("/dev/null", redacting.getString(2));
    }

    @Test
    public void testMultiple() throws Exception {
        final Cursor redacting = getContext().getContentResolver().query(
                Uri.parse("content://android.content.RedactingProvider/multiple"),
                null, null, null);

        redacting.moveToNext();
        assertEquals(null, redacting.getString(0));
        assertEquals("foo", redacting.getString(1));
        assertEquals(null, redacting.getString(2));
    }
}
