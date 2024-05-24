/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static org.junit.Assert.assertNull;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PoolsTest {
    private static final int POOL_SIZE = 2;

    private final Object mRed = new Object();
    private final Object mGreen = new Object();
    private final Object mBlue = new Object();

    @Test
    public void testSimple() throws Exception {
        doTest(new Pools.SimplePool<Object>(POOL_SIZE));
    }

    @Test
    public void testSynchronized() throws Exception {
        doTest(new Pools.SynchronizedPool<Object>(POOL_SIZE));
    }

    private void doTest(Pools.SimplePool<Object> pool) throws Exception {
        // Pools are empty by default
        assertNull(pool.acquire());

        // Verify single item in pool
        pool.release(mRed);
        assertEquals(mRed, pool.acquire());
        assertNull(pool.acquire());

        // Verify pool doesn't get over-full
        pool.release(mRed);
        pool.release(mGreen);
        pool.release(mBlue);
        assertEquals(mGreen, pool.acquire());
        assertEquals(mRed, pool.acquire());
        assertNull(pool.acquire());
    }
}
