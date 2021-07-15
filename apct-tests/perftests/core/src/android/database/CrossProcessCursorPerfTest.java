/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.database;

import static org.junit.Assert.assertEquals;

import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class CrossProcessCursorPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Measure transporting a small {@link Cursor}, roughly 1KB in size.
     */
    @Test
    public void timeSmall() throws Exception {
        time(1);
    }

    /**
     * Measure transporting a small {@link Cursor}, roughly 54KB in size.
     */
    @Test
    public void timeMedium() throws Exception {
        time(100);
    }

    /**
     * Measure transporting a small {@link Cursor}, roughly 5.4MB in size.
     */
    @Test
    public void timeLarge() throws Exception {
        time(10_000);
    }

    private static final Uri TEST_URI = Uri.parse("content://android.os.SomeProvider/");

    private void time(int count) throws Exception {
        try (ContentProviderClient client = InstrumentationRegistry.getTargetContext()
                .getContentResolver().acquireContentProviderClient(TEST_URI)) {
            // Configure remote side once with data size to return
            final ContentValues values = new ContentValues();
            values.put(Intent.EXTRA_INDEX, count);
            client.update(TEST_URI, values, null);

            // Repeatedly query that data until we reach convergence
            final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                try (Cursor c = client.query(TEST_URI, null, null, null)) {
                    // Actually walk the returned values to ensure we pull all
                    // data from the remote side
                    while (c.moveToNext()) {
                        assertEquals(c.getPosition(), c.getInt(0));
                    }
                }
            }
        }
    }
}
