/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import android.os.Process;

import com.google.android.collect.Lists;

import junit.framework.TestCase;
import junit.framework.Assert;

import java.util.ArrayList;

/**
 * Functional tests of EventLog.
 */

public class EventLogFunctionalTest extends TestCase {
    private static final String TAG = "EventLogFunctionalTest";

    private static final int TAG_SIZE = 4;
    private static final int TYPE_FIELD_SIZE = 1;
    private static final int STARTING_POS_OF_PAYLOAD = TAG_SIZE + TYPE_FIELD_SIZE;

    private static final int TEST_TAG = 42;
    private static final int TEST_TAG2 = 314;

    //todo:  For now all we do is test the returned length.  More to come.
    public void testLogOfPosInt() throws Exception {
        final int numBytes =  EventLog.writeEvent(TEST_TAG, 0x01020304);
        Assert.assertEquals(STARTING_POS_OF_PAYLOAD + 4, numBytes);
    }

    //todo:  For now all we do is test the returned length.  More to come.
    public void testLogOfPosLong() throws Exception {
        final int numBytes =  EventLog.writeEvent(TEST_TAG2, 0x0102030405060708L);
        Assert.assertEquals(STARTING_POS_OF_PAYLOAD + 8, numBytes);
    }

    //todo:  For now all we do is test the returned length.  More to come.
    public void testLogOfString() throws Exception {
        final String valueStr = "foo bar baz";
        final int numBytes =  EventLog.writeEvent(TEST_TAG, valueStr);
        Assert.assertEquals(STARTING_POS_OF_PAYLOAD + 4 + valueStr.length() + 1, numBytes);
     }

    public void testLogOfListWithOneInt() throws Exception {
        final EventLog.List list = new EventLog.List(1234);
        final int numBytes =  EventLog.writeEvent(TEST_TAG, list);
        Assert.assertEquals(STARTING_POS_OF_PAYLOAD + 1 + 1 + 4 + 1, numBytes);
    }

    public void testLogOfListWithMultipleInts() throws Exception {
       final EventLog.List list = new EventLog.List(1234, 2345, 3456);
        final int numBytes =  EventLog.writeEvent(TEST_TAG, list);
        Assert.assertEquals(STARTING_POS_OF_PAYLOAD + 1 + 1 + 4 + 1 + 4 + 1 + 4 + 1, numBytes);
    }

    public void testLogOfListWithEmbeddedList() throws Exception {
        final EventLog.List list = new EventLog.List(
                new EventLog.List(1234, 2345, 3456));
        final int numBytes =  EventLog.writeEvent(TEST_TAG, list);
        Assert.assertEquals(STARTING_POS_OF_PAYLOAD + 2 + 1 + 1 + 4 + 1 + 4 + 1 + 4 + 1, numBytes);
    }

    public void testEventLargerThanInitialBufferCapacity() throws Exception {
        final Integer[] array = new Integer[127];
        for (int i = 0; i < array.length; i++) {
            array[i] = i;
        }
        final EventLog.List list = new EventLog.List((Object[]) array);
        final int numBytes =  EventLog.writeEvent(TEST_TAG, list);
        Assert.assertEquals(STARTING_POS_OF_PAYLOAD + 1 + (5 * array.length) + 1, numBytes);
    }

    // This test is obsolete. See http://b/issue?id=1262082
    public void disableTestReadSimpleEvent() throws Exception {
        long when = System.currentTimeMillis();
        EventLog.writeEvent(2718, 12345);
        Log.i(TAG, "Wrote simple event at T=" + when);

        ArrayList<EventLog.Event> list = new ArrayList<EventLog.Event>();
        EventLog.readEvents(new int[] { 2718 }, list);

        boolean found = false;
        for (EventLog.Event event : list) {
            assertEquals(event.getTag(), 2718);
            long eventTime = event.getTimeNanos() / 1000000;
            Log.i(TAG, "  Found event T=" + eventTime);
            if (eventTime > when - 100 && eventTime < when + 1000) {
                assertEquals(event.getProcessId(), Process.myPid());
                assertEquals(event.getThreadId(), Process.myTid());
                assertEquals(event.getData(), 12345);

                assertFalse(found);
                found = true;
            }
        }

        assertTrue(found);
    }

    // This test is obsolete. See http://b/issue?id=1262082
    public void disableTestReadCompoundEntry() throws Exception {
        long when = System.currentTimeMillis();
        EventLog.writeEvent(2719,
                new EventLog.List(1l, new EventLog.List("2", "three", "4"), 5));
        Log.i(TAG, "Wrote compound event at T=" + when);

        ArrayList<EventLog.Event> list = new ArrayList<EventLog.Event>();
        EventLog.readEvents(new int[] { 2719 }, list);

        boolean found = false;
        for (EventLog.Event event : list) {
            long eventTime = event.getTimeNanos() / 1000000;
            Log.i(TAG, "  Found event T=" + eventTime);
            if (eventTime > when - 100 && eventTime < when + 1000) {
                EventLog.List data = (EventLog.List) event.getData();
                assertEquals(data.getNumItems(), 3);

                EventLog.List nested = (EventLog.List) data.getItem(1);
                assertEquals(nested.getNumItems(), 3);

                assertEquals(data.getItem(0), 1l);
                assertEquals(nested.getItem(0), "2");
                assertEquals(nested.getItem(1), "three");
                assertEquals(nested.getItem(2), "4");
                assertEquals(data.getItem(2), 5);

                assertFalse(found);
                found = true;
            }
        }

        assertTrue(found);
    }

    public void testEventLogTagsFile() throws Exception {
        EventLogTags tags = new EventLogTags();
        assertEquals(tags.get("answer").mTag, 42);
        assertEquals(tags.get("pi").mTag, 314);
        assertEquals(tags.get("e").mTag, 2718);
        assertEquals(tags.get(42).mName, "answer");
        assertEquals(tags.get(314).mName, "pi");
        assertEquals(tags.get(2718).mName, "e");
    }
}
