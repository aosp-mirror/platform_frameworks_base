/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.view;

import android.test.AndroidTestCase;

import androidx.test.filters.SmallTest;

public class HandlerActionQueueTest extends AndroidTestCase {

    @SmallTest
    public void testPostAndRemove() {
        HandlerActionQueue runQueue = new HandlerActionQueue();
        MockRunnable runnable1 = new MockRunnable();
        MockRunnable runnable2 = new MockRunnable();
        MockRunnable runnable3 = new MockRunnable();

        runQueue.post(runnable1);
        runQueue.post(runnable1);
        runQueue.post(runnable2);
        runQueue.postDelayed(runnable1, 100);
        runQueue.postDelayed(null, 500);
        assertEquals(5, runQueue.size());
        assertEquals(0, runQueue.getDelay(0));
        assertEquals(0, runQueue.getDelay(1));
        assertEquals(0, runQueue.getDelay(2));
        assertEquals(100, runQueue.getDelay(3));
        assertEquals(500, runQueue.getDelay(4));
        assertEquals(500, runQueue.getDelay(4));
        assertEquals(runnable1, runQueue.getRunnable(0));
        assertEquals(runnable1, runQueue.getRunnable(1));
        assertEquals(runnable2, runQueue.getRunnable(2));
        assertEquals(runnable1, runQueue.getRunnable(3));
        assertEquals(null, runQueue.getRunnable(4));

        runQueue.removeCallbacks(runnable1);
        assertEquals(2, runQueue.size());
        assertEquals(0, runQueue.getDelay(0));
        assertEquals(500, runQueue.getDelay(1));
        assertEquals(runnable2, runQueue.getRunnable(0));
        assertEquals(null, runQueue.getRunnable(1));

        try {
            assertNull(runQueue.getRunnable(2));
            assertFalse(true);
        } catch (IndexOutOfBoundsException e) {
            // Should throw an exception.
        }

        runQueue.removeCallbacks(runnable3);
        assertEquals(2, runQueue.size());

        runQueue.removeCallbacks(runnable2);
        assertEquals(1, runQueue.size());
        assertEquals(null, runQueue.getRunnable(0));

        runQueue.removeCallbacks(null);
        assertEquals(0, runQueue.size());
    }

    private static class MockRunnable implements Runnable {
        @Override
        public void run() {

        }
    }
}
