/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.util.Preconditions;
import com.android.internal.util.TraceBuffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link Watcher}, {@link Watchable}, {@link WatchableImpl},
 * {@link WatchedArrayMap}, {@link WatchedSparseArray}, and
 * {@link WatchedSparseBooleanArray}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:WatcherTest
 */
@SmallTest
public class WatcherTest {

    // A small Watchable leaf node
    private class Leaf extends WatchableImpl {
        private int datum = 0;
        void set(int i) {
            if (datum != i) {
                datum = i;
                dispatchChange(this);
            }
        }
        void tick() {
            set(datum + 1);
        }
    }

    // A top-most watcher.  It counts the number of notifications that it receives.
    private class Tester extends Watcher {
        // The count of changes.
        public int changes = 0;

        // The single Watchable that this monitors.
        public final Watchable mWatched;

        // The key, used for messages
        public String mKey;

        // Create the Tester with a Watcher
        public Tester(Watchable w, String k) {
            mWatched = w;
            mKey = k;
        }

        // Listen for events
        public void register() {
            mWatched.registerObserver(this);
        }

        // Stop listening for events
        public void unregister() {
            mWatched.unregisterObserver(this);
        }

        // Count the number of notifications received.
        @Override
        public void onChange(Watchable what) {
            changes++;
        }

        // Verify the count.
        public void verify(int want, String msg) {
            assertEquals(mKey + " " + msg, want, changes);
        }
    }

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void test_notify() {

        Tester tester;

        // Create a few leaves
        Leaf a = new Leaf();
        Leaf b = new Leaf();
        Leaf c = new Leaf();
        Leaf d = new Leaf();

        // Basic test.  Create a leaf and verify that changes to the leaf get notified to
        // the tester.
        tester = new Tester(a, "Leaf");
        tester.verify(0, "Initial leaf - no registration");
        a.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        a.tick();
        tester.verify(1, "Updates with registration");
        a.tick();
        a.tick();
        tester.verify(3, "Updates with registration");

        // Add the same leaf to more than one tester.  Verify that a change to the leaf is seen by
        // all registered listeners.
        Tester buddy1 = new Tester(a, "Leaf2");
        Tester buddy2 = new Tester(a, "Leaf3");
        buddy1.verify(0, "Initial leaf - no registration");
        buddy2.verify(0, "Initial leaf - no registration");
        a.tick();
        tester.verify(4, "Updates with buddies");
        buddy1.verify(0, "Updates - no registration");
        buddy2.verify(0, "Updates - no registration");
        buddy1.register();
        buddy2.register();
        buddy1.verify(0, "No updates - registered");
        buddy2.verify(0, "No updates - registered");
        a.tick();
        buddy1.verify(1, "First update");
        buddy2.verify(1, "First update");
        buddy1.unregister();
        a.tick();
        buddy1.verify(1, "Second update - unregistered");
        buddy2.verify(2, "Second update");

        buddy1 = null;
        buddy2 = null;

        final int INDEX_A = 1;
        final int INDEX_B = 2;
        final int INDEX_C = 3;
        final int INDEX_D = 4;

        // Test WatchedArrayMap
        WatchedArrayMap<Integer, Leaf> am = new WatchedArrayMap<>();
        am.put(INDEX_A, a);
        am.put(INDEX_B, b);
        tester = new Tester(am, "WatchedArrayMap");
        tester.verify(0, "Initial array - no registration");
        a.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        a.tick();
        tester.verify(1, "Updates with registration");
        b.tick();
        tester.verify(2, "Updates with registration");
        am.remove(INDEX_B);
        tester.verify(3, "Removed b");
        b.tick();
        tester.verify(3, "Updates with b not watched");
        am.put(INDEX_B, b);
        am.put(INDEX_C, b);
        tester.verify(5, "Added b twice");
        b.tick();
        tester.verify(6, "Changed b - single notification");
        am.remove(INDEX_C);
        tester.verify(7, "Removed first b");
        b.tick();
        tester.verify(8, "Changed b - single notification");
        am.remove(INDEX_B);
        tester.verify(9, "Removed second b");
        b.tick();
        tester.verify(9, "Updated b - no change");
        am.clear();
        tester.verify(10, "Cleared array");
        b.tick();
        tester.verify(10, "Change to b not in array");

        // Special methods
        am.put(INDEX_C, c);
        tester.verify(11, "Added c");
        c.tick();
        tester.verify(12, "Ticked c");
        am.setValueAt(am.indexOfKey(INDEX_C), d);
        tester.verify(13, "Replaced c with d");
        c.tick();
        d.tick();
        tester.verify(14, "Ticked d and c (c not registered)");

        am = null;

        // Test WatchedSparseArray
        WatchedSparseArray<Leaf> sa = new WatchedSparseArray<>();
        sa.put(INDEX_A, a);
        sa.put(INDEX_B, b);
        tester = new Tester(sa, "WatchedSparseArray");
        tester.verify(0, "Initial array - no registration");
        a.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        a.tick();
        tester.verify(1, "Updates with registration");
        b.tick();
        tester.verify(2, "Updates with registration");
        sa.remove(INDEX_B);
        tester.verify(3, "Removed b");
        b.tick();
        tester.verify(3, "Updates with b not watched");
        sa.put(INDEX_B, b);
        sa.put(INDEX_C, b);
        tester.verify(5, "Added b twice");
        b.tick();
        tester.verify(6, "Changed b - single notification");
        sa.remove(INDEX_C);
        tester.verify(7, "Removed first b");
        b.tick();
        tester.verify(8, "Changed b - single notification");
        sa.remove(INDEX_B);
        tester.verify(9, "Removed second b");
        b.tick();
        tester.verify(9, "Updated b - no change");
        sa.clear();
        tester.verify(10, "Cleared array");
        b.tick();
        tester.verify(10, "Change to b not in array");

        // Special methods
        sa.put(INDEX_A, a);
        sa.put(INDEX_B, b);
        sa.put(INDEX_C, c);
        tester.verify(13, "Added c");
        c.tick();
        tester.verify(14, "Ticked c");
        sa.setValueAt(sa.indexOfKey(INDEX_C), d);
        tester.verify(15, "Replaced c with d");
        c.tick();
        d.tick();
        tester.verify(16, "Ticked d and c (c not registered)");
        sa.append(INDEX_D, c);
        tester.verify(17, "Append c");
        c.tick();
        d.tick();
        tester.verify(19, "Ticked d and c");
        assertEquals("Verify four elements", 4, sa.size());
        // Figure out which elements are at which indices.
        Leaf[] x = new Leaf[4];
        for (int i = 0; i < 4; i++) {
            x[i] = sa.valueAt(i);
        }
        sa.removeAtRange(0, 2);
        tester.verify(20, "Removed two elements in one operation");
        x[0].tick();
        x[1].tick();
        tester.verify(20, "Ticked two removed elements");
        x[2].tick();
        x[3].tick();
        tester.verify(22, "Ticked two remaining elements");

        sa = null;

        // Test WatchedSparseBooleanArray
        WatchedSparseBooleanArray sb = new WatchedSparseBooleanArray();
        tester = new Tester(sb, "WatchedSparseBooleanArray");
        tester.verify(0, "Initial array - no registration");
        sb.put(INDEX_A, true);
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        sb.put(INDEX_B, true);
        tester.verify(1, "Updates with registration");
        sb.put(INDEX_B, true);
        tester.verify(1, "Null update");
        sb.put(INDEX_B, false);
        sb.put(INDEX_C, true);
        tester.verify(3, "Updates with registration");
        // Special methods
        sb.put(INDEX_C, true);
        tester.verify(3, "Added true, no change");
        sb.setValueAt(sb.indexOfKey(INDEX_C), false);
        tester.verify(4, "Replaced true with false");
        sb.append(INDEX_D, true);
        tester.verify(5, "Append true");

        sb = null;
    }
}
