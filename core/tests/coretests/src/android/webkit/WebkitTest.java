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

package android.webkit;

import android.test.AndroidTestCase;
import android.text.format.DateFormat;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;
import android.webkit.DateSorter;

import java.util.Calendar;
import java.util.Date;

public class WebkitTest extends AndroidTestCase {

    private static final String LOGTAG = WebkitTest.class.getName();

    @MediumTest
    public void testDateSorter() throws Exception {
        /**
         * Note: check the logging output manually to test
         * nothing automated yet, besides object creation
         */
        DateSorter dateSorter = new DateSorter(mContext);
        Date date = new Date();

        for (int i = 0; i < DateSorter.DAY_COUNT; i++) {
            Log.i(LOGTAG, "Boundary " + i + " " + dateSorter.getBoundary(i));
            Log.i(LOGTAG, "Label " + i + " " + dateSorter.getLabel(i));
        }

        Calendar c = Calendar.getInstance();
        long time = c.getTimeInMillis();
        int index;
        Log.i(LOGTAG, "now: " + dateSorter.getIndex(time));
        for (int i = 0; i < 20; i++) {
            time -= 8 * 60 * 60 * 1000; // 8 hours
            date.setTime(time);
            c.setTime(date);
            index = dateSorter.getIndex(time);
            Log.i(LOGTAG, "time: " + DateFormat.format("yyyy/MM/dd kk:mm:ss", c).toString() +
                    " " + index + " " + dateSorter.getLabel(index));
        }
    }
}
