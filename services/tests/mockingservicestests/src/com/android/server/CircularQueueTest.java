/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Test {@link CircularQueue}.
 */
public class CircularQueueTest {

    private CircularQueue<Integer, String> mQueue;
    private static final int LIMIT = 2;

    @Test
    public void testQueueInsertionAndDeletion() {
        mQueue = new CircularQueue<>(LIMIT);
        mQueue.put(1, "A");
        assertEquals(mQueue.getElement(1), "A");
        mQueue.removeElement(1);
        assertNull(mQueue.getElement(1));
    }

    @Test
    public void testQueueLimit() {
        mQueue = new CircularQueue<>(LIMIT);
        mQueue.put(1, "A");
        mQueue.put(2, "B");
        String removedElement = mQueue.put(3, "C");
        assertNull(mQueue.getElement(1));
        assertEquals(mQueue.getElement(2), "B");
        assertEquals(mQueue.getElement(3), "C");
        // Confirming that put is returning the deleted element
        assertEquals(removedElement, "A");
    }

    @Test
    public void testQueueElements() {
        mQueue = new CircularQueue<>(LIMIT);
        mQueue.put(1, "A");
        mQueue.put(2, "B");
        assertEquals(mQueue.values().size(), 2);
        mQueue.put(3, "C");
        assertEquals(mQueue.values().size(), 2);
    }
}
