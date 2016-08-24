/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content;

import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

import java.util.Observable;
import java.util.Observer;

/** Test of {@link ContentQueryMap} */
@Suppress  // Failing.
public class ContentQueryMapTest extends AndroidTestCase {
    /** Helper class to run test code in a new thread with a Looper. */
    private abstract class LooperThread extends Thread {
        public Throwable mError = null;
        public boolean mSuccess = false;

        abstract void go();

        public void run() {
            try {
                Looper.prepare();
                go();
                Looper.loop();
            } catch (Throwable e) {
                mError = e;
            }
        }
    }

    @MediumTest
    public void testContentQueryMap() throws Throwable {
        LooperThread thread = new LooperThread() {
            void go() {
                ContentResolver r = getContext().getContentResolver();
                Settings.System.putString(r, "test", "Value");
                Cursor cursor = r.query(
                        Settings.System.CONTENT_URI,
                        new String[] {
                            Settings.System.NAME,
                            Settings.System.VALUE,
                        }, null, null, null);

                final ContentQueryMap cqm = new ContentQueryMap(
                        cursor, Settings.System.NAME, true, null);
                // Get the current state of the CQM. This forces a requery and means that the
                // call to getValues() below won't do a requery().
                cqm.getRows();
                
                // The cache won't notice changes until the loop runs.
                Settings.System.putString(r, "test", "New Value");
                ContentValues v = cqm.getValues("test");
                String value = v.getAsString(Settings.System.VALUE);
                assertEquals("Value", value);

                // Use an Observer to find out when the cache does update.
                cqm.addObserver(new Observer() {
                    public void update(Observable o, Object arg) {
                        // Should have the new values by now.
                        ContentValues v = cqm.getValues("test");
                        String value = v.getAsString(Settings.System.VALUE);
                        assertEquals("New Value", value);
                        Looper.myLooper().quit();
                        cqm.close();
                        mSuccess = true;
                    }
                });

                // Give up after a few seconds, if it doesn't.
                new Handler().postDelayed(new Runnable() {
                    public void run() {
                        fail("Timed out");
                    }
                }, 5000);
            }
        };

        thread.start();
        thread.join();
        if (thread.mError != null) throw thread.mError;
        assertTrue(thread.mSuccess);
    }
}
