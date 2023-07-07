/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.midi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.midi.MidiEventMultiScheduler;
import com.android.internal.midi.MidiEventScheduler;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

/**
 * Unit tests for com.android.internal.midi.MidiEventMultiScheduler.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class MidiEventMultiSchedulerTest {
    private byte[] generateRandomByteStream(Random rnd, int size) {
        byte[] output = new byte[size];
        rnd.nextBytes(output);
        return output;
    }

    private void compareByteArrays(byte[] expectedArray, byte[] outputArray) {
        assertEquals(expectedArray.length, outputArray.length);
        for (int i = 0; i < outputArray.length; i++) {
            assertEquals(expectedArray[i], outputArray[i]);
        }
    }

    private long timeFromNow(long milliseconds) {
        return System.nanoTime() + 1000000L * milliseconds;
    }

    @Test
    public void testMultiScheduler() {
        try {
            MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(3);
            assertEquals(3, multiScheduler.getNumEventSchedulers());
            MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
            MidiEventScheduler scheduler1 = multiScheduler.getEventScheduler(1);
            MidiEventScheduler scheduler2 = multiScheduler.getEventScheduler(2);

            scheduler0.add(scheduler0.createScheduledEvent(new byte[]{(byte) 0xf0, (byte) 0xf7},
                    0, 2, timeFromNow(100)));
            scheduler1.add(scheduler1.createScheduledEvent(new byte[]{(byte) 0xf1, (byte) 0xf2},
                    0, 2, timeFromNow(200)));
            scheduler2.add(scheduler2.createScheduledEvent(new byte[]{(byte) 0xf3, (byte) 0xf4},
                    0, 2, timeFromNow(300)));
            scheduler0.add(scheduler0.createScheduledEvent(new byte[]{(byte) 0xf5, (byte) 0xf6},
                    0, 2, timeFromNow(400)));
            assertTrue(multiScheduler.waitNextEvent());
            assertNotNull(scheduler0.getNextEvent(System.nanoTime()));
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            assertNull(scheduler2.getNextEvent(System.nanoTime()));
            assertTrue(multiScheduler.waitNextEvent());
            assertNull(scheduler0.getNextEvent(System.nanoTime()));
            assertNotNull(scheduler1.getNextEvent(System.nanoTime()));
            assertNull(scheduler2.getNextEvent(System.nanoTime()));
            assertTrue(multiScheduler.waitNextEvent());
            assertNull(scheduler0.getNextEvent(System.nanoTime()));
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            assertNotNull(scheduler2.getNextEvent(System.nanoTime()));
            assertTrue(multiScheduler.waitNextEvent());
            assertNotNull(scheduler0.getNextEvent(System.nanoTime()));
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            assertNull(scheduler2.getNextEvent(System.nanoTime()));
        } catch (InterruptedException ex) {

        }
    }

    @Test
    public void testSchedulerLargeData() {
        try {
            MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(1);
            assertEquals(1, multiScheduler.getNumEventSchedulers());
            MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);

            Random rnd = new Random(42);

            final int arraySize = 1000;
            byte[] expectedArray = generateRandomByteStream(rnd, arraySize);

            scheduler0.add(scheduler0.createScheduledEvent(expectedArray, 0, arraySize,
                    timeFromNow(100)));
            assertTrue(multiScheduler.waitNextEvent());
            MidiEventScheduler.MidiEvent event =
                    (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            compareByteArrays(expectedArray, event.data);
        } catch (InterruptedException ex) {

        }
    }

    @Test
    public void testSchedulerClose() {
        try {
            MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(1);
            assertEquals(1, multiScheduler.getNumEventSchedulers());
            MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
            scheduler0.close();
            // After all schedulers are closed, waitNextEvent() should return false.
            assertFalse(multiScheduler.waitNextEvent());
        } catch (InterruptedException ex) {

        }
    }

    @Test
    public void testSchedulerMultiClose() {
        try {
            MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(3);
            assertEquals(3, multiScheduler.getNumEventSchedulers());
            multiScheduler.close();
            // After all schedulers are closed, waitNextEvent() should return false.
            assertFalse(multiScheduler.waitNextEvent());
        } catch (InterruptedException ex) {

        }
    }

    @Test
    public void testSchedulerNoPreemptiveClose() {
        try {
            MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(3);
            assertEquals(3, multiScheduler.getNumEventSchedulers());
            MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
            MidiEventScheduler scheduler1 = multiScheduler.getEventScheduler(1);
            MidiEventScheduler scheduler2 = multiScheduler.getEventScheduler(2);
            scheduler0.close();
            scheduler1.close();
            scheduler2.add(scheduler2.createScheduledEvent(new byte[]{(byte) 0xf5, (byte) 0xf6},
                    0, 2, timeFromNow(100)));
            assertTrue(multiScheduler.waitNextEvent());
            scheduler2.close();
            // After all schedulers are closed, waitNextEvent() should return false.
            assertFalse(multiScheduler.waitNextEvent());
        } catch (InterruptedException ex) {

        }
    }

    @Test
    public void testSchedulerSpamEvents() {
        MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(1);
        assertEquals(1, multiScheduler.getNumEventSchedulers());
        MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
        // Create a msg with size 1
        byte[] msg = new byte[1];
        for (int i = 0; i < 1000; i++) {
            msg[0] = (byte) i;
            scheduler0.add(scheduler0.createScheduledEvent(msg, 0, 1, timeFromNow(0)));
            MidiEventScheduler.MidiEvent event =
                    (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals(msg[0], event.data[0]);
        }
        assertNull(scheduler0.getNextEvent(System.nanoTime()));
    }

    @Test
    public void testSchedulerSpamEventsPullLater() {
        MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(1);
        assertEquals(1, multiScheduler.getNumEventSchedulers());
        MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
        // Create a msg with size 1
        byte[] msg = new byte[1];
        for (int i = 0; i < 1000; i++) {
            msg[0] = (byte) i;
            scheduler0.add(scheduler0.createScheduledEvent(msg, 0, 1, timeFromNow(0)));
        }

        for (int i = 0; i < 1000; i++) {
            MidiEventScheduler.MidiEvent event =
                    (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) i, event.data[0]);
        }
        assertNull(scheduler0.getNextEvent(System.nanoTime()));
    }

    @Test
    public void testSchedulerSpamEventsCallbackLater() {
        MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(1);
        assertEquals(1, multiScheduler.getNumEventSchedulers());
        MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
        // Create a msg with size 1
        byte[] msg = new byte[1];
        for (int i = 0; i < 1000; i++) {
            msg[0] = (byte) i;
            scheduler0.add(scheduler0.createScheduledEvent(msg, 0, 1, timeFromNow(0)));
        }

        for (int i = 0; i < 1000; i++) {
            try {
                assertTrue(multiScheduler.waitNextEvent());
            } catch (InterruptedException ex) {
            }
            MidiEventScheduler.MidiEvent event =
                    (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) i, event.data[0]);
        }
        assertNull(scheduler0.getNextEvent(System.nanoTime()));
    }

    @Test
    public void testMultiSchedulerOutOfOrder() {
        try {
            MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(3);
            assertEquals(3, multiScheduler.getNumEventSchedulers());
            MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
            MidiEventScheduler scheduler1 = multiScheduler.getEventScheduler(1);
            MidiEventScheduler scheduler2 = multiScheduler.getEventScheduler(2);

            scheduler0.add(scheduler0.createScheduledEvent(new byte[]{(byte) 0xf3},
                    0, 1,
                    timeFromNow(400)));
            scheduler2.add(scheduler2.createScheduledEvent(new byte[]{(byte) 0xf2},
                    0, 1,
                    timeFromNow(300)));
            scheduler1.add(scheduler1.createScheduledEvent(new byte[]{(byte) 0xf1},
                    0, 1,
                    timeFromNow(200)));
            scheduler0.add(scheduler0.createScheduledEvent(new byte[]{(byte) 0xf0},
                    0, 1,
                    timeFromNow(100)));

            assertTrue(multiScheduler.waitNextEvent());
            MidiEventScheduler.MidiEvent event =
                    (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf0, event.data[0]);
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            assertNull(scheduler2.getNextEvent(System.nanoTime()));
            assertTrue(multiScheduler.waitNextEvent());
            assertNull(scheduler0.getNextEvent(System.nanoTime()));
            event = (MidiEventScheduler.MidiEvent) scheduler1.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf1, event.data[0]);
            assertNull(scheduler2.getNextEvent(System.nanoTime()));
            assertTrue(multiScheduler.waitNextEvent());
            assertNull(scheduler0.getNextEvent(System.nanoTime()));
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            event = (MidiEventScheduler.MidiEvent) scheduler2.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf2, event.data[0]);
            assertTrue(multiScheduler.waitNextEvent());
            event = (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf3, event.data[0]);
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            assertNull(scheduler2.getNextEvent(System.nanoTime()));
        } catch (InterruptedException ex) {

        }
    }

    @Test
    public void testMultiSchedulerOutOfOrderNegativeTime() {
        try {
            MidiEventMultiScheduler multiScheduler = new MidiEventMultiScheduler(3);
            assertEquals(3, multiScheduler.getNumEventSchedulers());
            MidiEventScheduler scheduler0 = multiScheduler.getEventScheduler(0);
            MidiEventScheduler scheduler1 = multiScheduler.getEventScheduler(1);
            MidiEventScheduler scheduler2 = multiScheduler.getEventScheduler(2);

            scheduler0.add(scheduler0.createScheduledEvent(new byte[]{(byte) 0xf3},
                    0, 1,
                    timeFromNow(-100)));
            scheduler2.add(scheduler2.createScheduledEvent(new byte[]{(byte) 0xf2},
                    0, 1,
                    timeFromNow(-200)));
            scheduler1.add(scheduler1.createScheduledEvent(new byte[]{(byte) 0xf1},
                    0, 1,
                    timeFromNow(-300)));
            scheduler0.add(scheduler0.createScheduledEvent(new byte[]{(byte) 0xf0},
                    0, 1,
                    timeFromNow(-400)));

            assertTrue(multiScheduler.waitNextEvent());
            MidiEventScheduler.MidiEvent event =
                    (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf0, event.data[0]);
            assertTrue(multiScheduler.waitNextEvent());
            event = (MidiEventScheduler.MidiEvent) scheduler1.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf1, event.data[0]);
            assertTrue(multiScheduler.waitNextEvent());
            event = (MidiEventScheduler.MidiEvent) scheduler2.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf2, event.data[0]);
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            assertTrue(multiScheduler.waitNextEvent());
            event = (MidiEventScheduler.MidiEvent) scheduler0.getNextEvent(System.nanoTime());
            assertNotNull(event);
            assertEquals(1, event.count);
            assertEquals((byte) 0xf3, event.data[0]);
            assertNull(scheduler1.getNextEvent(System.nanoTime()));
            assertNull(scheduler2.getNextEvent(System.nanoTime()));
        } catch (InterruptedException ex) {

        }
    }
}
