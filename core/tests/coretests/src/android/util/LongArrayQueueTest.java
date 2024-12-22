/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.NoSuchElementException;

/**
 * Internal tests for {@link LongArrayQueue}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LongArrayQueueTest {

    private LongArrayQueue mQueueUnderTest;

    @Before
    public void setUp() {
        mQueueUnderTest = new LongArrayQueue();
    }

    @Test
    public void removeFirstOnEmptyQueue() {
        try {
            mQueueUnderTest.removeFirst();
            fail("removeFirst() succeeded on an empty queue!");
        } catch (NoSuchElementException e) {
        }
        mQueueUnderTest.addLast(5);
        mQueueUnderTest.removeFirst();
        try {
            mQueueUnderTest.removeFirst();
            fail("removeFirst() succeeded on an empty queue!");
        } catch (NoSuchElementException e) {
        }
    }

    @Test
    public void addLastRemoveFirstFifo() {
        mQueueUnderTest.addLast(1);
        assertEquals(1, mQueueUnderTest.removeFirst());
        int n = 890;
        int removes = 0;
        for (int i = 0; i < n; i++) {
            mQueueUnderTest.addLast(i);
            if ((i % 3) == 0) {
                assertEquals(removes++, mQueueUnderTest.removeFirst());
            }
        }
        while (removes < n) {
            assertEquals(removes++, mQueueUnderTest.removeFirst());
        }
    }

    @Test
    public void peekFirstOnEmptyQueue() {
        try {
            mQueueUnderTest.peekFirst();
            fail("peekFirst() succeeded on an empty queue!");
        } catch (NoSuchElementException e) {
        }
        mQueueUnderTest.addLast(5);
        mQueueUnderTest.removeFirst();
        try {
            mQueueUnderTest.peekFirst();
            fail("peekFirst() succeeded on an empty queue!");
        } catch (NoSuchElementException e) {
        }
    }

    @Test
    public void peekFirstChanges() {
        mQueueUnderTest.addLast(1);
        assertEquals(1, mQueueUnderTest.peekFirst());
        mQueueUnderTest.addLast(2);
        mQueueUnderTest.addLast(3);
        mQueueUnderTest.addLast(4);
        // addLast() has no effect on peekFirst().
        assertEquals(1, mQueueUnderTest.peekFirst());
        mQueueUnderTest.removeFirst();
        mQueueUnderTest.removeFirst();
        assertEquals(3, mQueueUnderTest.peekFirst());
    }

    @Test
    public void peekLastOnEmptyQueue() {
        try {
            mQueueUnderTest.peekLast();
            fail("peekLast() succeeded on an empty queue!");
        } catch (NoSuchElementException e) {
        }
        mQueueUnderTest.addLast(5);
        mQueueUnderTest.removeFirst();
        try {
            mQueueUnderTest.peekLast();
            fail("peekLast() succeeded on an empty queue!");
        } catch (NoSuchElementException e) {
        }
    }

    @Test
    public void peekLastChanges() {
        mQueueUnderTest.addLast(1);
        assertEquals(1, mQueueUnderTest.peekLast());
        mQueueUnderTest.addLast(2);
        mQueueUnderTest.addLast(3);
        mQueueUnderTest.addLast(4);
        assertEquals(4, mQueueUnderTest.peekLast());
        mQueueUnderTest.removeFirst();
        mQueueUnderTest.removeFirst();
        // removeFirst() has no effect on peekLast().
        assertEquals(4, mQueueUnderTest.peekLast());
    }

    @Test
    public void peekFirstVsPeekLast() {
        mQueueUnderTest.addLast(2);
        assertEquals(mQueueUnderTest.peekFirst(), mQueueUnderTest.peekLast());
        mQueueUnderTest.addLast(3);
        assertNotEquals(mQueueUnderTest.peekFirst(), mQueueUnderTest.peekLast());
        mQueueUnderTest.removeFirst();
        assertEquals(mQueueUnderTest.peekFirst(), mQueueUnderTest.peekLast());
    }

    @Test
    public void peekFirstVsRemoveFirst() {
        int n = 25;
        for (int i = 0; i < n; i++) {
            mQueueUnderTest.addLast(i + 1);
        }
        for (int i = 0; i < n; i++) {
            long peekVal = mQueueUnderTest.peekFirst();
            assertEquals(peekVal, mQueueUnderTest.removeFirst());
        }
    }

    @Test
    public void sizeOfEmptyQueue() {
        assertEquals(0, mQueueUnderTest.size());
        mQueueUnderTest = new LongArrayQueue(1000);
        // capacity doesn't affect size.
        assertEquals(0, mQueueUnderTest.size());
    }

    @Test
    public void sizeAfterOperations() {
        final int added = 1200;
        for (int i = 0; i < added; i++) {
            mQueueUnderTest.addLast(i);
        }
        // each add increments the size by 1.
        assertEquals(added, mQueueUnderTest.size());
        mQueueUnderTest.peekLast();
        mQueueUnderTest.peekFirst();
        // peeks don't change the size.
        assertEquals(added, mQueueUnderTest.size());
        final int removed = 345;
        for (int i = 0; i < removed; i++) {
            mQueueUnderTest.removeFirst();
        }
        // each remove decrements the size by 1.
        assertEquals(added - removed, mQueueUnderTest.size());
        mQueueUnderTest.clear();
        // clear reduces the size to 0.
        assertEquals(0, mQueueUnderTest.size());
    }

    @Test
    public void getInvalidPositions() {
        try {
            mQueueUnderTest.get(0);
            fail("get(0) succeeded on an empty queue!");
        } catch (IndexOutOfBoundsException e) {
        }
        int n = 520;
        for (int i = 0; i < 2 * n; i++) {
            mQueueUnderTest.addLast(i + 1);
        }
        for (int i = 0; i < n; i++) {
            mQueueUnderTest.removeFirst();
        }
        try {
            mQueueUnderTest.get(-3);
            fail("get(-3) succeeded");
        } catch (IndexOutOfBoundsException e) {
        }
        assertEquals(n, mQueueUnderTest.size());
        try {
            mQueueUnderTest.get(n);
            fail("get(" + n + ") succeeded on a queue with " + n + " elements");
        } catch (IndexOutOfBoundsException e) {
        }
        try {
            mQueueUnderTest.get(n + 3);
            fail("get(" + (n + 3) + ") succeeded on a queue with " + n + " elements");
        } catch (IndexOutOfBoundsException e) {
        }
    }

    @Test
    public void getValidPositions() {
        int added = 423;
        int removed = 212;
        for (int i = 0; i < added; i++) {
            mQueueUnderTest.addLast(i);
        }
        for (int i = 0; i < removed; i++) {
            mQueueUnderTest.removeFirst();
        }
        for (int i = 0; i < (added - removed); i++) {
            assertEquals(removed + i, mQueueUnderTest.get(i));
        }
    }
}
