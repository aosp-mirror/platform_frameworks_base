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

package android.os;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = FileObserver.class)
public class FileObserverTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private Observer mObserver;
    private File mTestFile;

    private static class Observer extends FileObserver {
        public List<Map> events = Lists.newArrayList();
        public int totalEvents = 0;

        public Observer(String path) {
            super(path);
        }

        public void onEvent(int event, String path) {
            synchronized (this) {
                totalEvents++;
                Map<String, Object> map = Maps.newHashMap();

                map.put("event", event);
                map.put("path", path);

                events.add(map);

                this.notifyAll();
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        mTestFile = File.createTempFile(".file_observer_test", ".txt");
    }

    @After
    public void tearDown() throws Exception {
        if (mTestFile != null && mTestFile.exists()) {
            mTestFile.delete();
        }
    }

    @Test
    @MediumTest
    public void testRun() throws Exception {
        // make file changes and wait for them
        assertTrue(mTestFile.exists());
        assertNotNull(mTestFile.getParent());

        mObserver = new Observer(mTestFile.getParent());
        mObserver.startWatching();

        FileOutputStream out = new FileOutputStream(mTestFile);
        try {
            out.write(0x20);
            waitForEvent(); // open
            waitForEvent(); // modify

            mTestFile.delete();
            waitForEvent(); // modify
            waitForEvent(); // delete

            mObserver.stopWatching();

            // Ensure that we have seen at least 3 events.
            assertTrue(mObserver.totalEvents > 3);
        } finally {
            out.close();
        }
    }

    private void waitForEvent() {
        synchronized (mObserver) {
            boolean done = false;
            while (!done) {
                try {
                    mObserver.wait(2000);
                    done = true;
                } catch (InterruptedException e) {
                }
            }

            Iterator<Map> it = mObserver.events.iterator();

            while (it.hasNext()) {
                Map map = it.next();
                Log.i("FileObserverTest", "event: " + getEventString((Integer)map.get("event")) + " path: " + map.get("path"));
            }

            mObserver.events.clear();
        }
    }

    private String getEventString(int event) {
        switch (event) {
            case  FileObserver.ACCESS:
                return "ACCESS";
            case FileObserver.MODIFY:
                return "MODIFY";
            case FileObserver.ATTRIB:
                return "ATTRIB";
            case FileObserver.CLOSE_WRITE:
                return "CLOSE_WRITE";
            case FileObserver.CLOSE_NOWRITE:
                return "CLOSE_NOWRITE";
            case FileObserver.OPEN:
                return "OPEN";
            case FileObserver.MOVED_FROM:
                return "MOVED_FROM";
            case FileObserver.MOVED_TO:
                return "MOVED_TO";
            case FileObserver.CREATE:
                return "CREATE";
            case FileObserver.DELETE:
                return "DELETE";
            case FileObserver.DELETE_SELF:
                return "DELETE_SELF";
            case FileObserver.MOVE_SELF:
                return "MOVE_SELF";
            default:
                return "UNKNOWN";
        }
    }
}
