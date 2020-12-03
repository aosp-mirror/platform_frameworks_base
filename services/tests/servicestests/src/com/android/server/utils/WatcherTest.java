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
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;

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

    // A counter to generate unique IDs for Leaf elements.
    private int mLeafId = 0;

    // Useful indices used int the tests.
    private static final int INDEX_A = 1;
    private static final int INDEX_B = 2;
    private static final int INDEX_C = 3;
    private static final int INDEX_D = 4;

    // A small Watchable leaf node
    private class Leaf extends WatchableImpl implements Snappable {
        private int mId;
        private int mDatum;

        Leaf() {
            mDatum = 0;
            mId = mLeafId++;
        }

        void set(int i) {
            if (mDatum != i) {
                mDatum = i;
                dispatchChange(this);
            }
        }
        int get() {
            return mDatum;
        }
        void tick() {
            set(mDatum + 1);
        }
        public Leaf snapshot() {
            Leaf result = new Leaf();
            result.mDatum = mDatum;
            result.mId = mId;
            result.seal();
            return result;
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof Leaf) {
                return mDatum == ((Leaf) o).mDatum && mId == ((Leaf) o).mId;
            } else {
                return false;
            }
        }
        @Override
        public String toString() {
            return "Leaf(" + mDatum + "," + mId + ")";
        }
    }

    // Execute the {@link Runnable} and if {@link UnsupportedOperationException} is
    // thrown, do nothing.  If no exception is thrown, fail the test.
    private void verifySealed(String msg, Runnable test) {
        try {
            test.run();
            fail(msg + " should be sealed");
        } catch (IllegalStateException e) {
            // The exception was expected.
        }
    }

    // Execute the {@link Runnable} and if {@link UnsupportedOperationException} is
    // thrown, fail the test.  If no exception is thrown, do nothing.
    private void verifyNotSealed(String msg, Runnable test) {
        try {
            test.run();
        } catch (IllegalStateException e) {
            fail(msg + " should be not sealed");
        }
    }

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testBasicBehavior() {
        WatchableTester tester;

        // Create a few leaves
        Leaf leafA = new Leaf();

        // Basic test.  Create a leaf and verify that changes to the leaf get notified to
        // the tester.
        tester = new WatchableTester(leafA, "Leaf");
        tester.verify(0, "Initial leaf - no registration");
        leafA.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        leafA.tick();
        tester.verify(1, "Updates with registration");
        leafA.tick();
        leafA.tick();
        tester.verify(3, "Updates with registration");
        // Create a snapshot.  Verify that the snapshot matches the
        Leaf leafASnapshot = leafA.snapshot();
        assertEquals("Leaf snapshot", leafA.get(), leafASnapshot.get());
        leafA.tick();
        assertTrue(leafA.get() != leafASnapshot.get());
        tester.verify(4, "Tick after snapshot");
        verifySealed("Leaf", ()->leafASnapshot.tick());

        // Add the same leaf to more than one tester.  Verify that a change to the leaf is seen by
        // all registered listeners.
        tester.clear();
        WatchableTester buddy1 = new WatchableTester(leafA, "Leaf2");
        WatchableTester buddy2 = new WatchableTester(leafA, "Leaf3");
        buddy1.verify(0, "Initial leaf - no registration");
        buddy2.verify(0, "Initial leaf - no registration");
        leafA.tick();
        tester.verify(1, "Updates with buddies");
        buddy1.verify(0, "Updates - no registration");
        buddy2.verify(0, "Updates - no registration");
        buddy1.register();
        buddy2.register();
        buddy1.verify(0, "No updates - registered");
        buddy2.verify(0, "No updates - registered");
        leafA.tick();
        buddy1.verify(1, "First update");
        buddy2.verify(1, "First update");
        buddy1.unregister();
        leafA.tick();
        buddy1.verify(1, "Second update - unregistered");
        buddy2.verify(2, "Second update");
    }

    @Test
    public void testWatchedArrayMap() {
        WatchableTester tester;

        // Create a few leaves
        Leaf leafA = new Leaf();
        Leaf leafB = new Leaf();
        Leaf leafC = new Leaf();
        Leaf leafD = new Leaf();

        // Test WatchedArrayMap
        WatchedArrayMap<Integer, Leaf> array = new WatchedArrayMap<>();
        array.put(INDEX_A, leafA);
        array.put(INDEX_B, leafB);
        tester = new WatchableTester(array, "WatchedArrayMap");
        tester.verify(0, "Initial array - no registration");
        leafA.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        leafA.tick();
        tester.verify(1, "Updates with registration");
        leafB.tick();
        tester.verify(2, "Updates with registration");
        array.remove(INDEX_B);
        tester.verify(3, "Removed b");
        leafB.tick();
        tester.verify(3, "Updates with b not watched");
        array.put(INDEX_B, leafB);
        array.put(INDEX_C, leafB);
        tester.verify(5, "Added b twice");
        leafB.tick();
        tester.verify(6, "Changed b - single notification");
        array.remove(INDEX_C);
        tester.verify(7, "Removed first b");
        leafB.tick();
        tester.verify(8, "Changed b - single notification");
        array.remove(INDEX_B);
        tester.verify(9, "Removed second b");
        leafB.tick();
        tester.verify(9, "Updated b - no change");
        array.clear();
        tester.verify(10, "Cleared array");
        leafB.tick();
        tester.verify(10, "Change to b not in array");

        // Special methods
        array.put(INDEX_C, leafC);
        tester.verify(11, "Added c");
        leafC.tick();
        tester.verify(12, "Ticked c");
        array.setValueAt(array.indexOfKey(INDEX_C), leafD);
        tester.verify(13, "Replaced c with d");
        leafC.tick();
        leafD.tick();
        tester.verify(14, "Ticked d and c (c not registered)");

        // Snapshot
        {
            final WatchedArrayMap<Integer, Leaf> arraySnap = array.snapshot();
            tester.verify(14, "Generate snapshot (no changes)");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals("WatchedArrayMap snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                for (int j = 0; j < arraySnap.size(); j++) {
                    assertTrue("WatchedArrayMap elements differ",
                               array.valueAt(i) != arraySnap.valueAt(j));
                }
                assertTrue("WatchedArrayMap element copy",
                           array.valueAt(i).equals(arraySnap.valueAt(i)));
            }
            leafD.tick();
            tester.verify(15, "Tick after snapshot");
            // Verify that the snapshot is sealed
            verifySealed("WatchedArrayMap", ()->arraySnap.put(INDEX_A, leafA));
        }
        // Recreate the snapshot since the test corrupted it.
        {
            final WatchedArrayMap<Integer, Leaf> arraySnap = array.snapshot();
            // Verify that elements are also snapshots
            final Leaf arraySnapElement = arraySnap.valueAt(0);
            verifySealed("ArraySnapshotElement", ()->arraySnapElement.tick());
        }
    }

    @Test
    public void testWatchedSparseArray() {
        WatchableTester tester;

        // Create a few leaves
        Leaf leafA = new Leaf();
        Leaf leafB = new Leaf();
        Leaf leafC = new Leaf();
        Leaf leafD = new Leaf();

        // Test WatchedSparseArray
        WatchedSparseArray<Leaf> array = new WatchedSparseArray<>();
        array.put(INDEX_A, leafA);
        array.put(INDEX_B, leafB);
        tester = new WatchableTester(array, "WatchedSparseArray");
        tester.verify(0, "Initial array - no registration");
        leafA.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        leafA.tick();
        tester.verify(1, "Updates with registration");
        leafB.tick();
        tester.verify(2, "Updates with registration");
        array.remove(INDEX_B);
        tester.verify(3, "Removed b");
        leafB.tick();
        tester.verify(3, "Updates with b not watched");
        array.put(INDEX_B, leafB);
        array.put(INDEX_C, leafB);
        tester.verify(5, "Added b twice");
        leafB.tick();
        tester.verify(6, "Changed b - single notification");
        array.remove(INDEX_C);
        tester.verify(7, "Removed first b");
        leafB.tick();
        tester.verify(8, "Changed b - single notification");
        array.remove(INDEX_B);
        tester.verify(9, "Removed second b");
        leafB.tick();
        tester.verify(9, "Updated leafB - no change");
        array.clear();
        tester.verify(10, "Cleared array");
        leafB.tick();
        tester.verify(10, "Change to b not in array");

        // Special methods
        array.put(INDEX_A, leafA);
        array.put(INDEX_B, leafB);
        array.put(INDEX_C, leafC);
        tester.verify(13, "Added c");
        leafC.tick();
        tester.verify(14, "Ticked c");
        array.setValueAt(array.indexOfKey(INDEX_C), leafD);
        tester.verify(15, "Replaced c with d");
        leafC.tick();
        leafD.tick();
        tester.verify(16, "Ticked d and c (c not registered)");
        array.append(INDEX_D, leafC);
        tester.verify(17, "Append c");
        leafC.tick();
        leafD.tick();
        tester.verify(19, "Ticked d and c");
        assertEquals("Verify four elements", 4, array.size());
        // Figure out which elements are at which indices.
        Leaf[] x = new Leaf[4];
        for (int i = 0; i < 4; i++) {
            x[i] = array.valueAt(i);
        }
        array.removeAtRange(0, 2);
        tester.verify(20, "Removed two elements in one operation");
        x[0].tick();
        x[1].tick();
        tester.verify(20, "Ticked two removed elements");
        x[2].tick();
        x[3].tick();
        tester.verify(22, "Ticked two remaining elements");

        // Snapshot
        {
            final WatchedSparseArray<Leaf> arraySnap = array.snapshot();
            tester.verify(22, "Generate snapshot (no changes)");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals("WatchedSparseArray snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                for (int j = 0; j < arraySnap.size(); j++) {
                    assertTrue("WatchedSparseArray elements differ",
                               array.valueAt(i) != arraySnap.valueAt(j));
                }
                assertTrue("WatchedArrayMap element copy",
                           array.valueAt(i).equals(arraySnap.valueAt(i)));
            }
            leafD.tick();
            tester.verify(23, "Tick after snapshot");
            // Verify that the array snapshot is sealed
            verifySealed("WatchedSparseArray", ()->arraySnap.put(INDEX_A, leafB));
        }
        // Recreate the snapshot since the test corrupted it.
        {
            final WatchedSparseArray<Leaf> arraySnap = array.snapshot();
            // Verify that elements are also snapshots
            final Leaf arraySnapElement = arraySnap.valueAt(0);
            verifySealed("ArraySnapshotElement", ()->arraySnapElement.tick());
        }
    }

    @Test
    public void testWatchedSparseBooleanArray() {
        WatchableTester tester;

        // Test WatchedSparseBooleanArray
        WatchedSparseBooleanArray array = new WatchedSparseBooleanArray();
        tester = new WatchableTester(array, "WatchedSparseBooleanArray");
        tester.verify(0, "Initial array - no registration");
        array.put(INDEX_A, true);
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        array.put(INDEX_B, true);
        tester.verify(1, "Updates with registration");
        array.put(INDEX_B, true);
        tester.verify(1, "Null update");
        array.put(INDEX_B, false);
        array.put(INDEX_C, true);
        tester.verify(3, "Updates with registration");
        // Special methods
        array.put(INDEX_C, true);
        tester.verify(3, "Added true, no change");
        array.setValueAt(array.indexOfKey(INDEX_C), false);
        tester.verify(4, "Replaced true with false");
        array.append(INDEX_D, true);
        tester.verify(5, "Append true");

        // Snapshot
        {
            WatchedSparseBooleanArray arraySnap = array.snapshot();
            tester.verify(5, "Generate snapshot");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals("WatchedSparseBooleanArray snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                assertEquals("WatchedSparseArray element copy",
                             array.valueAt(i), arraySnap.valueAt(i));
            }
            array.put(INDEX_D, false);
            tester.verify(6, "Tick after snapshot");
            // Verify that the array is sealed
            verifySealed("WatchedSparseBooleanArray", ()->arraySnap.put(INDEX_D, false));
        }
    }
}
