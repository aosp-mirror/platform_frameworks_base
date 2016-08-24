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

package android.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony.Sms;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;

import java.util.GregorianCalendar;

@Suppress  // Failing.
public class SmsProviderTest extends AndroidTestCase {

    @LargeTest
    public void testProvider() throws Exception {
        // This test does the following
        //  1. Insert 10 messages from the same number at different times.
        //
        //  . Delete the messages and make sure that they were deleted.

        long now = System.currentTimeMillis();

        Uri[] urls = new Uri[10];
        String[] dates = new String[]{
                Long.toString(new GregorianCalendar(1970, 1, 1, 0, 0, 0).getTimeInMillis()),
                Long.toString(new GregorianCalendar(1971, 2, 13, 16, 35, 3).getTimeInMillis()),
                Long.toString(new GregorianCalendar(1978, 10, 22, 0, 1, 0).getTimeInMillis()),
                Long.toString(new GregorianCalendar(1980, 1, 11, 10, 22, 30).getTimeInMillis()),
                Long.toString(now - (5 * 24 * 60 * 60 * 1000)),
                Long.toString(now - (2 * 24 * 60 * 60 * 1000)),
                Long.toString(now - (5 * 60 * 60 * 1000)),
                Long.toString(now - (30 * 60 * 1000)),
                Long.toString(now - (5 * 60 * 1000)),
                Long.toString(now)
        };

        ContentValues map = new ContentValues();
        map.put("address", "+15045551337");
        map.put("read", 0);

        ContentResolver contentResolver = mContext.getContentResolver();

        for (int i = 0; i < urls.length; i++) {
            map.put("body", "Test " + i + " !");
            map.put("date", dates[i]);
            urls[i] = contentResolver.insert(Sms.Inbox.CONTENT_URI, map);
            assertNotNull(urls[i]);
        }

        Cursor c = contentResolver.query(Sms.Inbox.CONTENT_URI, null, null, null, "date");

        //DatabaseUtils.dumpCursor(c);

        for (Uri url : urls) {
            int count = contentResolver.delete(url, null, null);
            assertEquals(1, count);
        }
    }
}
