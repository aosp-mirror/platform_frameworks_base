/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.EventLog.Event;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import junit.framework.AssertionFailedError;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link android.util.EventLog} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class EventLogTest {
    @Rule public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testSimple() throws Throwable {
        EventLog.writeEvent(42, 42);
        EventLog.writeEvent(42, 42L);
        EventLog.writeEvent(42, 42f);
        EventLog.writeEvent(42, "forty-two");
        EventLog.writeEvent(42, 42, "forty-two", null, 42);
    }

    @Test
    @IgnoreUnderRavenwood(reason = "Reading not yet supported")
    public void testWithNewData() throws Throwable {
        Event event = createEvent(() -> {
            EventLog.writeEvent(314,  123);
        }, 314);

        assertTrue(event.withNewData(12345678L).getData().equals(12345678L));
        assertTrue(event.withNewData(2.718f).getData().equals(2.718f));
        assertTrue(event.withNewData("test string").getData().equals("test string"));

        Object[] objects = ((Object[]) event.withNewData(
                new Object[] {111, 2.22f, 333L, "444"}).getData());
        assertEquals(4, objects.length);
        assertTrue(objects[0].equals(111));
        assertTrue(objects[1].equals(2.22f));
        assertTrue(objects[2].equals(333L));
        assertTrue(objects[3].equals("444"));
    }

    /**
     * Creates an Event object. Only the native code has the serialization and deserialization logic
     * so need to actually emit a real log in order to generate the object.
     */
    private Event createEvent(Runnable generator, int expectedTag) throws Exception {
        Long markerData = System.currentTimeMillis();
        EventLog.writeEvent(expectedTag, markerData);
        generator.run();

        List<Event> events = new ArrayList<>();
        // Give the message some time to show up in the log
        Thread.sleep(20);
        EventLog.readEvents(new int[] {expectedTag}, events);
        for (int i = 0; i < events.size() - 1; i++) {
            if (markerData.equals(events.get(i).getData())) {
                return events.get(i + 1);
            }
        }
        throw new AssertionFailedError("Unable to locate marker event");
    }
}
