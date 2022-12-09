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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Random;

/**
 * Test class for various utility classes that support the Watchable or Snappable
 * features.  This covers {@link Watcher}, {@link Watchable}, {@link WatchableImpl},
 * {@link WatchedArrayMap}, {@link WatchedSparseArray}, and
 * {@link WatchedSparseBooleanArray}.
 *
 * Build/Install/Run:
 *  atest PackageManagerServiceTest:WatcherTest
 */
@SmallTest
public class WatcherTest {

    // A counter to generate unique IDs for Leaf elements.
    private int mLeafId = 0;

    // Useful indices used in the tests.
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
        final String name = "WatchedArrayMap";
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
        tester = new WatchableTester(array, name);
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
            assertEquals(name + " snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                for (int j = 0; j < arraySnap.size(); j++) {
                    assertTrue(name + " elements differ",
                               array.valueAt(i) != arraySnap.valueAt(j));
                }
                assertTrue(name + " element copy",
                           array.valueAt(i).equals(arraySnap.valueAt(i)));
            }
            leafD.tick();
            tester.verify(15, "Tick after snapshot");
            // Verify that the snapshot is sealed
            verifySealed(name, ()->arraySnap.put(INDEX_A, leafA));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        // Recreate the snapshot since the test corrupted it.
        {
            final WatchedArrayMap<Integer, Leaf> arraySnap = array.snapshot();
            // Verify that elements are also snapshots
            final Leaf arraySnapElement = arraySnap.valueAt(0);
            verifySealed("ArraySnapshotElement", ()->arraySnapElement.tick());
        }
        // Verify copy-in/out
        {
            final String msg = name + " copy-in/out failed";
            ArrayMap<Integer, Leaf> base = new ArrayMap<>();
            array.copyTo(base);
            WatchedArrayMap<Integer, Leaf> copy = new WatchedArrayMap<>();
            copy.copyFrom(base);
            if (!array.equals(copy)) {
                fail(msg);
            }
        }
    }

    @Test
    public void testWatchedArraySet() {
        final String name = "WatchedArraySet";
        WatchableTester tester;

        // Create a few leaves
        Leaf leafA = new Leaf();
        Leaf leafB = new Leaf();
        Leaf leafC = new Leaf();
        Leaf leafD = new Leaf();

        // Test WatchedArraySet
        WatchedArraySet<Leaf> array = new WatchedArraySet<>();
        array.add(leafA);
        array.add(leafB);
        tester = new WatchableTester(array, name);
        tester.verify(0, "Initial array - no registration");
        leafA.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        leafA.tick();
        tester.verify(1, "Updates with registration");
        leafB.tick();
        tester.verify(2, "Updates with registration");
        array.remove(leafB);
        tester.verify(3, "Removed b");
        leafB.tick();
        tester.verify(3, "Updates with b not watched");
        array.add(leafB);
        array.add(leafB);
        tester.verify(5, "Added b once");
        leafB.tick();
        tester.verify(6, "Changed b - single notification");
        array.remove(leafB);
        tester.verify(7, "Removed b");
        leafB.tick();
        tester.verify(7, "Changed b - not watched");
        array.remove(leafB);
        tester.verify(7, "Removed non-existent b");
        array.clear();
        tester.verify(8, "Cleared array");
        leafA.tick();
        tester.verify(8, "Change to a not in array");

        // Special methods
        array.add(leafA);
        array.add(leafB);
        array.add(leafC);
        tester.verify(11, "Added a, b, c");
        leafC.tick();
        tester.verify(12, "Ticked c");
        array.removeAt(array.indexOf(leafC));
        tester.verify(13, "Removed c");
        leafC.tick();
        tester.verify(13, "Ticked c, not registered");
        array.append(leafC);
        tester.verify(14, "Append c");
        leafC.tick();
        leafD.tick();
        tester.verify(15, "Ticked d and c");
        assertEquals("Verify three elements", 3, array.size());

        // Snapshot
        {
            final WatchedArraySet<Leaf> arraySnap = array.snapshot();
            tester.verify(15, "Generate snapshot (no changes)");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals(name + " snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                for (int j = 0; j < arraySnap.size(); j++) {
                    assertTrue(name + " elements differ",
                               array.valueAt(i) != arraySnap.valueAt(j));
                }
            }
            leafC.tick();
            tester.verify(16, "Tick after snapshot");
            // Verify that the array snapshot is sealed
            verifySealed(name, ()->arraySnap.add(leafB));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        // Recreate the snapshot since the test corrupted it.
        {
            final WatchedArraySet<Leaf> arraySnap = array.snapshot();
            // Verify that elements are also snapshots
            final Leaf arraySnapElement = arraySnap.valueAt(0);
            verifySealed(name + " snap element", ()->arraySnapElement.tick());
        }
        // Verify copy-in/out
        {
            final String msg = name + " copy-in/out";
            ArraySet<Leaf> base = new ArraySet<>();
            array.copyTo(base);
            WatchedArraySet<Leaf> copy = new WatchedArraySet<>();
            copy.copyFrom(base);
            if (!array.equals(copy)) {
                fail(msg);
            }
        }
    }

    @Test
    public void testWatchedArrayList() {
        final String name = "WatchedArrayList";
        WatchableTester tester;

        // Create a few leaves
        Leaf leafA = new Leaf();
        Leaf leafB = new Leaf();
        Leaf leafC = new Leaf();
        Leaf leafD = new Leaf();

        // Redefine the indices used in the tests to be zero-based
        final int indexA = 0;
        final int indexB = 1;
        final int indexC = 2;
        final int indexD = 3;

        // Test WatchedArrayList
        WatchedArrayList<Leaf> array = new WatchedArrayList<>();
        // A spacer that takes up index 0 (and is not Watchable).
        array.add(indexA, leafA);
        array.add(indexB, leafB);
        tester = new WatchableTester(array, name);
        tester.verify(0, "Initial array - no registration");
        leafA.tick();
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        leafA.tick();
        tester.verify(1, "Updates with registration");
        leafB.tick();
        tester.verify(2, "Updates with registration");
        array.remove(indexB);
        tester.verify(3, "Removed b");
        leafB.tick();
        tester.verify(3, "Updates with b not watched");
        array.add(indexB, leafB);
        array.add(indexC, leafB);
        tester.verify(5, "Added b twice");
        leafB.tick();
        tester.verify(6, "Changed b - single notification");
        array.remove(indexC);
        tester.verify(7, "Removed first b");
        leafB.tick();
        tester.verify(8, "Changed b - single notification");
        array.remove(indexB);
        tester.verify(9, "Removed second b");
        leafB.tick();
        tester.verify(9, "Updated leafB - no change");
        array.clear();
        tester.verify(10, "Cleared array");
        leafB.tick();
        tester.verify(10, "Change to b not in array");

        // Special methods
        array.add(indexA, leafA);
        array.add(indexB, leafB);
        array.add(indexC, leafC);
        tester.verify(13, "Added c");
        leafC.tick();
        tester.verify(14, "Ticked c");
        array.set(array.indexOf(leafC), leafD);
        tester.verify(15, "Replaced c with d");
        leafC.tick();
        leafD.tick();
        tester.verify(16, "Ticked d and c (c not registered)");
        array.add(leafC);
        tester.verify(17, "Append c");
        leafC.tick();
        leafD.tick();
        tester.verify(19, "Ticked d and c");

        // Snapshot
        {
            final WatchedArrayList<Leaf> arraySnap = array.snapshot();
            tester.verify(19, "Generate snapshot (no changes)");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals(name + " snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                for (int j = 0; j < arraySnap.size(); j++) {
                    assertTrue(name + " elements differ",
                               array.get(i) != arraySnap.get(j));
                }
                assertTrue(name + " element copy",
                           array.get(i).equals(arraySnap.get(i)));
            }
            leafD.tick();
            tester.verify(20, "Tick after snapshot");
            // Verify that the array snapshot is sealed
            verifySealed(name, ()->arraySnap.add(indexA, leafB));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        // Recreate the snapshot since the test corrupted it.
        {
            final WatchedArrayList<Leaf> arraySnap = array.snapshot();
            // Verify that elements are also snapshots
            final Leaf arraySnapElement = arraySnap.get(0);
            verifySealed("ArraySnapshotElement", ()->arraySnapElement.tick());
        }
        // Verify copy-in/out
        {
            final String msg = name + " copy-in/out";
            ArrayList<Leaf> base = new ArrayList<>();
            array.copyTo(base);
            WatchedArrayList<Leaf> copy = new WatchedArrayList<>();
            copy.copyFrom(base);
            if (!array.equals(copy)) {
                fail(msg);
            }
        }
    }

    @Test
    public void testWatchedSparseArray() {
        final String name = "WatchedSparseArray";
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
        tester = new WatchableTester(array, name);
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
            assertEquals(name + " snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                for (int j = 0; j < arraySnap.size(); j++) {
                    assertTrue(name + " elements differ",
                               array.valueAt(i) != arraySnap.valueAt(j));
                }
                assertTrue(name + " element copy",
                           array.valueAt(i).equals(arraySnap.valueAt(i)));
            }
            leafD.tick();
            tester.verify(23, "Tick after snapshot");
            // Verify that the array snapshot is sealed
            verifySealed(name, ()->arraySnap.put(INDEX_A, leafB));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        // Recreate the snapshot since the test corrupted it.
        {
            final WatchedSparseArray<Leaf> arraySnap = array.snapshot();
            // Verify that elements are also snapshots
            final Leaf arraySnapElement = arraySnap.valueAt(0);
            verifySealed("ArraySnapshotElement", ()->arraySnapElement.tick());
        }
        // Verify copy-in/out
        {
            final String msg = name + " copy-in/out";
            SparseArray<Leaf> base = new SparseArray<>();
            array.copyTo(base);
            WatchedSparseArray<Leaf> copy = new WatchedSparseArray<>();
            copy.copyFrom(base);
            final int end = array.size();
            assertTrue(msg + " size mismatch " + end + " " + copy.size(), end == copy.size());
            for (int i = 0; i < end; i++) {
                final int key = array.keyAt(i);
                assertTrue(msg, array.get(i) == copy.get(i));
            }
        }
    }

    @Test
    public void testWatchedLongSparseArray() {
        final String name = "WatchedLongSparseArray";
        WatchableTester tester;

        // Create a few leaves
        Leaf leafA = new Leaf();
        Leaf leafB = new Leaf();
        Leaf leafC = new Leaf();
        Leaf leafD = new Leaf();

        // Test WatchedLongSparseArray
        WatchedLongSparseArray<Leaf> array = new WatchedLongSparseArray<>();
        array.put(INDEX_A, leafA);
        array.put(INDEX_B, leafB);
        tester = new WatchableTester(array, name);
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
        tester.verify(15, "Ticked c (c not registered)");
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
        array.removeAt(1);
        tester.verify(20, "Removed one element");
        x[1].tick();
        tester.verify(20, "Ticked one removed element");
        x[2].tick();
        tester.verify(21, "Ticked one remaining element");

        // Snapshot
        {
            final WatchedLongSparseArray<Leaf> arraySnap = array.snapshot();
            tester.verify(21, "Generate snapshot (no changes)");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals(name + " snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                for (int j = 0; j < arraySnap.size(); j++) {
                    assertTrue(name + " elements differ",
                               array.valueAt(i) != arraySnap.valueAt(j));
                }
                assertTrue(name + " element copy",
                           array.valueAt(i).equals(arraySnap.valueAt(i)));
            }
            leafD.tick();
            tester.verify(22, "Tick after snapshot");
            // Verify that the array snapshot is sealed
            verifySealed(name, ()->arraySnap.put(INDEX_A, leafB));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        // Recreate the snapshot since the test corrupted it.
        {
            final WatchedLongSparseArray<Leaf> arraySnap = array.snapshot();
            // Verify that elements are also snapshots
            final Leaf arraySnapElement = arraySnap.valueAt(0);
            verifySealed("ArraySnapshotElement", ()->arraySnapElement.tick());
        }
        // Verify copy-in/out
        {
            final String msg = name + " copy-in/out";
            LongSparseArray<Leaf> base = new LongSparseArray<>();
            array.copyTo(base);
            WatchedLongSparseArray<Leaf> copy = new WatchedLongSparseArray<>();
            copy.copyFrom(base);
            final int end = array.size();
            assertTrue(msg + " size mismatch " + end + " " + copy.size(), end == copy.size());
            for (int i = 0; i < end; i++) {
                final long key = array.keyAt(i);
                assertTrue(msg, array.get(i) == copy.get(i));
            }
        }
    }

    @Test
    public void testWatchedSparseBooleanArray() {
        final String name = "WatchedSparseBooleanArray";
        WatchableTester tester;

        // Test WatchedSparseBooleanArray
        WatchedSparseBooleanArray array = new WatchedSparseBooleanArray();
        tester = new WatchableTester(array, name);
        tester.verify(0, "Initial array - no registration");
        array.put(INDEX_A, true);
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        array.put(INDEX_B, true);
        tester.verify(1, "Updates with registration");
        array.put(INDEX_B, false);
        array.put(INDEX_C, true);
        tester.verify(3, "Updates with registration");
        // Special methods
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
            verifySealed(name, ()->arraySnap.put(INDEX_D, false));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        // Verify copy-in/out
        {
            final String msg = name + " copy-in/out";
            SparseBooleanArray base = new SparseBooleanArray();
            array.copyTo(base);
            WatchedSparseBooleanArray copy = new WatchedSparseBooleanArray();
            copy.copyFrom(base);
            final int end = array.size();
            assertTrue(msg + " size mismatch/2 " + end + " " + copy.size(), end == copy.size());
            for (int i = 0; i < end; i++) {
                final int key = array.keyAt(i);
                assertTrue(msg + " element", array.get(i) == copy.get(i));
            }
        }
    }

    @Test
    public void testWatchedSparseIntArray() {
        final String name = "WatchedSparseIntArray";
        WatchableTester tester;

        // Test WatchedSparseIntArray
        WatchedSparseIntArray array = new WatchedSparseIntArray();
        tester = new WatchableTester(array, name);
        tester.verify(0, "Initial array - no registration");
        array.put(INDEX_A, 1);
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        array.put(INDEX_B, 2);
        tester.verify(1, "Updates with registration");
        array.put(INDEX_B, 4);
        array.put(INDEX_C, 5);
        tester.verify(3, "Updates with registration");
        // Special methods
        array.setValueAt(array.indexOfKey(INDEX_C), 7);
        tester.verify(4, "Replaced 6 with 7");
        array.append(INDEX_D, 8);
        tester.verify(5, "Append 8");

        // Snapshot
        {
            WatchedSparseIntArray arraySnap = array.snapshot();
            tester.verify(5, "Generate snapshot");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals("WatchedSparseIntArray snap same size",
                         array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                assertEquals(name + " element copy",
                             array.valueAt(i), arraySnap.valueAt(i));
            }
            array.put(INDEX_D, 9);
            tester.verify(6, "Tick after snapshot");
            // Verify that the array is sealed
            verifySealed(name, ()->arraySnap.put(INDEX_D, 10));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        // Verify copy-in/out
        {
            final String msg = name + " copy-in/out";
            SparseIntArray base = new SparseIntArray();
            array.copyTo(base);
            WatchedSparseIntArray copy = new WatchedSparseIntArray();
            copy.copyFrom(base);
            final int end = array.size();
            assertTrue(msg + " size mismatch " + end + " " + copy.size(), end == copy.size());
            for (int i = 0; i < end; i++) {
                final int key = array.keyAt(i);
                assertTrue(msg, array.get(i) == copy.get(i));
            }
        }
    }

    @Test
    public void testWatchedSparseSetArray() {
        final String name = "WatchedSparseSetArray";
        WatchableTester tester;

        // Test WatchedSparseSetArray
        WatchedSparseSetArray array = new WatchedSparseSetArray();
        tester = new WatchableTester(array, name);
        tester.verify(0, "Initial array - no registration");
        array.add(INDEX_A, 1);
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        array.add(INDEX_B, 2);
        tester.verify(1, "Updates with registration");
        array.add(INDEX_B, 4);
        array.add(INDEX_C, 5);
        tester.verify(3, "Updates with registration");
        // Special methods
        assertTrue(array.remove(INDEX_C, 5));
        tester.verify(4, "Removed 5 from key 3");
        array.remove(INDEX_B);
        tester.verify(5, "Removed everything for key 2");

        // Snapshot
        {
            WatchedSparseSetArray arraySnap = (WatchedSparseSetArray) array.snapshot();
            tester.verify(5, "Generate snapshot");
            // Verify that the snapshot is a proper copy of the source.
            assertEquals("WatchedSparseSetArray snap same size",
                    array.size(), arraySnap.size());
            for (int i = 0; i < array.size(); i++) {
                ArraySet set = array.get(array.keyAt(i));
                ArraySet setSnap = arraySnap.get(arraySnap.keyAt(i));
                assertNotNull(set);
                assertTrue(set.equals(setSnap));
            }
            array.add(INDEX_D, 9);
            tester.verify(6, "Tick after snapshot");
            // Verify that the array is sealed
            verifySealed(name, ()->arraySnap.add(INDEX_D, 10));
            assertTrue(!array.isSealed());
            assertTrue(arraySnap.isSealed());
        }
        array.clear();
        tester.verify(7, "Cleared all entries");
    }

    private static class IndexGenerator {
        private final int mSeed;
        private final Random mRandom;
        public IndexGenerator(int seed) {
            mSeed = seed;
            mRandom = new Random(mSeed);
        }
        public int next() {
            return mRandom.nextInt(50000);
        }
        public void reset() {
            mRandom.setSeed(mSeed);
        }
        // This is an inefficient way to know if a value appears in an array.
        private boolean contains(int[] s, int length, int k) {
            for (int i = 0; i < length; i++) {
                if (s[i] == k) {
                    return true;
                }
            }
            return false;
        }
        public int[] indexes(int size) {
            reset();
            int[] r = new int[size];
            for (int i = 0; i < size; i++) {
                int key = next();
                // Ensure the list of indices are unique.
                while (contains(r, i, key)) {
                    key = next();
                }
                r[i] = key;
            }
            return r;
        }
    }

    // Return a value based on the row and column.  The algorithm tries to avoid simple
    // patterns like checkerboard.
    private final boolean cellValue(int row, int col) {
        return (((row * 4 + col) % 3)& 1) == 1;
    }

    // Fill a matrix
    private void fill(WatchedSparseBooleanMatrix matrix, int size, int[] indexes) {
        for (int i = 0; i < size; i++) {
            int row = indexes[i];
            for (int j = 0; j < size; j++) {
                int col = indexes[j];
                boolean want = cellValue(i, j);
                matrix.put(row, col, want);
            }
        }
    }

    // Fill new cells in the matrix which has enlarged capacity.
    private void fillNew(WatchedSparseBooleanMatrix matrix, int initialCapacity,
            int newCapacity, int[] indexes) {
        final int size = newCapacity;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i < initialCapacity && j < initialCapacity) {
                    // Do not touch old cells
                    continue;
                }
                final int row = indexes[i];
                final int col = indexes[j];
                matrix.put(row, col, cellValue(i, j));
            }
        }
    }

    // Verify the content of a matrix.  This asserts on mismatch.  Selected indices may
    // have been deleted.
    private void verify(WatchedSparseBooleanMatrix matrix, int[] indexes, boolean[] absent) {
        for (int i = 0; i < matrix.size(); i++) {
            int row = indexes[i];
            for (int j = 0; j < matrix.size(); j++) {
                int col = indexes[j];
                if (absent != null && (absent[i] || absent[j])) {
                    boolean want = false;
                    String msg = String.format("matrix(%d:%d, %d:%d) (deleted)", i, row, j, col);
                    assertEquals(msg, matrix.get(row, col), false);
                    assertEquals(msg, matrix.get(row, col, false), false);
                    assertEquals(msg, matrix.get(row, col, true), true);
                } else {
                    boolean want = cellValue(i, j);
                    String msg = String.format("matrix(%d:%d, %d:%d)", i, row, j, col);
                    assertEquals(msg, matrix.get(row, col), want);
                    assertEquals(msg, matrix.get(row, col, false), want);
                    assertEquals(msg, matrix.get(row, col, true), want);
                }
            }
        }
    }

    private void matrixGrow(WatchedSparseBooleanMatrix matrix, int size, IndexGenerator indexer) {
        int[] indexes = indexer.indexes(size);

        // Set values in the matrix, then read back and verify.
        fill(matrix, size, indexes);
        assertEquals(matrix.size(), size);
        verify(matrix, indexes, null);

        // Test the keyAt/indexOfKey methods
        for (int i = 0; i < matrix.size(); i++) {
            int key = indexes[i];
            assertEquals(matrix.keyAt(matrix.indexOfKey(key)), key);
        }
    }

    private void matrixDelete(WatchedSparseBooleanMatrix matrix, int size, IndexGenerator indexer) {
        int[] indexes = indexer.indexes(size);
        fill(matrix, size, indexes);

        // Delete a bunch of rows.  Verify that reading back results in false and that
        // contains() is false.  Recreate the rows and verify that all cells (other than
        // the one just created) are false.
        boolean[] absent = new boolean[size];
        for (int i = 0; i < size; i += 13) {
            matrix.deleteKey(indexes[i]);
            absent[i] = true;
        }
        verify(matrix, indexes, absent);
    }

    private void matrixShrink(WatchedSparseBooleanMatrix matrix, int size, IndexGenerator indexer) {
        int[] indexes = indexer.indexes(size);
        fill(matrix, size, indexes);

        int initialCapacity = matrix.capacity();

        // Delete every other row, remembering which rows were deleted.  The goal is to
        // make room for compaction.
        boolean[] absent = new boolean[size];
        for (int i = 0; i < size; i += 2) {
            matrix.deleteKey(indexes[i]);
            absent[i] = true;
        }

        matrix.compact();
        int finalCapacity = matrix.capacity();
        assertTrue("Matrix shrink", initialCapacity > finalCapacity);
        assertTrue("Matrix shrink", finalCapacity - matrix.size() < matrix.STEP);
    }

    private void matrixSetCapacity(WatchedSparseBooleanMatrix matrix, int newCapacity,
            IndexGenerator indexer) {
        final int initialCapacity = matrix.capacity();
        final int[] indexes = indexer.indexes(Math.max(initialCapacity, newCapacity));
        fill(matrix, initialCapacity, indexes);

        matrix.setCapacity(newCapacity);
        fillNew(matrix, initialCapacity, newCapacity, indexes);

        assertEquals(matrix.size(), indexes.length);
        verify(matrix, indexes, null);
        // Test the keyAt/indexOfKey methods
        for (int i = 0; i < matrix.size(); i++) {
            int key = indexes[i];
            assertEquals(matrix.keyAt(matrix.indexOfKey(key)), key);
        }
    }

    @Test
    public void testWatchedSparseBooleanMatrix() {
        final String name = "WatchedSparseBooleanMatrix";

        // Test the core matrix functionality.  The three tess are meant to test various
        // combinations of auto-grow.
        IndexGenerator indexer = new IndexGenerator(3);
        matrixGrow(new WatchedSparseBooleanMatrix(), 10, indexer);
        matrixGrow(new WatchedSparseBooleanMatrix(1000), 500, indexer);
        matrixGrow(new WatchedSparseBooleanMatrix(1000), 2000, indexer);
        matrixDelete(new WatchedSparseBooleanMatrix(), 500, indexer);
        matrixShrink(new WatchedSparseBooleanMatrix(), 500, indexer);

        // Test Watchable behavior.
        WatchedSparseBooleanMatrix matrix = new WatchedSparseBooleanMatrix();
        WatchableTester tester = new WatchableTester(matrix, name);
        tester.verify(0, "Initial array - no registration");
        matrix.put(INDEX_A, INDEX_A, true);
        tester.verify(0, "Updates with no registration");
        tester.register();
        tester.verify(0, "Updates with no registration");
        matrix.put(INDEX_A, INDEX_B, true);
        tester.verify(1, "Single cell assignment");
        matrix.put(INDEX_A, INDEX_B, true);
        tester.verify(2, "Single cell assignment - same value");
        matrix.put(INDEX_C, INDEX_B, true);
        tester.verify(3, "Single cell assignment");
        matrix.deleteKey(INDEX_B);
        tester.verify(4, "Delete key");
        assertEquals(matrix.get(INDEX_B, INDEX_C), false);
        assertEquals(matrix.get(INDEX_B, INDEX_C, false), false);
        assertEquals(matrix.get(INDEX_B, INDEX_C, true), true);

        matrix.clear();
        tester.verify(5, "Clear");
        assertEquals(matrix.size(), 0);
        fill(matrix, 10, indexer.indexes(10));
        int[] keys = matrix.keys();
        assertEquals(keys.length, matrix.size());
        for (int i = 0; i < matrix.size(); i++) {
            assertEquals(matrix.keyAt(i), keys[i]);
        }

        WatchedSparseBooleanMatrix a = new WatchedSparseBooleanMatrix();
        matrixGrow(a, 10, indexer);
        assertEquals(a.size(), 10);
        WatchedSparseBooleanMatrix b = new WatchedSparseBooleanMatrix();
        matrixGrow(b, 10, indexer);
        assertEquals(b.size(), 10);
        assertEquals(a.equals(b), true);
        int rowIndex = b.keyAt(3);
        int colIndex = b.keyAt(4);
        b.put(rowIndex, colIndex, !b.get(rowIndex, colIndex));
        assertEquals(a.equals(b), false);

        // Test Snappable behavior.
        WatchedSparseBooleanMatrix s = a.snapshot();
        assertEquals(a.equals(s), true);
        a.put(rowIndex, colIndex, !a.get(rowIndex, colIndex));
        assertEquals(a.equals(s), false);

        // Verify copy-in/out
        {
            final String msg = name + " copy";
            WatchedSparseBooleanMatrix copy = new WatchedSparseBooleanMatrix();
            copy.copyFrom(matrix);
            final int end = copy.size();
            assertTrue(msg + " size mismatch " + end + " " + matrix.size(), end == matrix.size());
            for (int i = 0; i < end; i++) {
                assertEquals(copy.keyAt(i), keys[i]);
            }
        }
    }

    @Test
    public void testWatchedSparseBooleanMatrix_setCapacity() {
        final IndexGenerator indexer = new IndexGenerator(3);
        matrixSetCapacity(new WatchedSparseBooleanMatrix(500), 1000, indexer);
        matrixSetCapacity(new WatchedSparseBooleanMatrix(1000), 500, indexer);
    }

    @Test
    public void testWatchedSparseBooleanMatrix_removeRangeAndShrink() {
        final IndexGenerator indexer = new IndexGenerator(3);
        final int initialCapacity = 500;
        final int removeCounts = 33;
        final WatchedSparseBooleanMatrix matrix = new WatchedSparseBooleanMatrix(initialCapacity);
        final int[] indexes = indexer.indexes(initialCapacity);
        final boolean[] absents = new boolean[initialCapacity];
        fill(matrix, initialCapacity, indexes);
        assertEquals(matrix.size(), initialCapacity);

        for (int i = 0; i < initialCapacity / removeCounts; i++) {
            final int size = matrix.size();
            final int fromIndex = (size / 2 < removeCounts ? 0 : size / 2 - removeCounts);
            final int toIndex = (fromIndex + removeCounts > size ? size : fromIndex + removeCounts);
            for (int index = fromIndex; index < toIndex; index++) {
                final int key = matrix.keyAt(index);
                for (int j = 0; j < indexes.length; j++) {
                    if (key == indexes[j]) {
                        absents[j] = true;
                        break;
                    }
                }
            }
            matrix.removeRange(fromIndex, toIndex);
            assertEquals(matrix.size(), size - (toIndex - fromIndex));
            verify(matrix, indexes, absents);

            matrix.compact();
            verify(matrix, indexes, absents);
        }
    }

    @Test
    public void testNestedArrays() {
        final String name = "NestedArrays";
        WatchableTester tester;

        // Create a few leaves
        Leaf leafA = new Leaf();
        Leaf leafB = new Leaf();
        Leaf leafC = new Leaf();
        Leaf leafD = new Leaf();

        // Test nested arrays.
        WatchedLongSparseArray<Leaf> lsaA = new WatchedLongSparseArray<>();
        lsaA.put(2, leafA);
        WatchedLongSparseArray<Leaf> lsaB = new WatchedLongSparseArray<>();
        lsaB.put(4, leafB);
        WatchedLongSparseArray<Leaf> lsaC = new WatchedLongSparseArray<>();
        lsaC.put(6, leafC);

        WatchedArrayMap<String, WatchedLongSparseArray<Leaf>> array =
                new WatchedArrayMap<>();
        array.put("A", lsaA);
        array.put("B", lsaB);

        // Test WatchedSparseIntArray
        tester = new WatchableTester(array, name);
        tester.verify(0, "Initial array - no registration");
        tester.register();
        tester.verify(0, "Initial array - post registration");
        leafA.tick();
        tester.verify(1, "tick grand-leaf");
        lsaA.put(2, leafD);
        tester.verify(2, "replace leafA");
        leafA.tick();
        tester.verify(2, "tick unregistered leafA");
        leafD.tick();
        tester.verify(3, "tick leafD");
    }

    @Test
    public void testSnapshotCache() {
        final String name = "SnapshotCache";
        WatchableTester tester;

        Leaf leafA = new Leaf();
        SnapshotCache<Leaf> cache = new SnapshotCache<>(leafA, leafA) {
                @Override
                public Leaf createSnapshot() {
                    return mSource.snapshot();
                }};

        Leaf s1 = cache.snapshot();
        assertTrue(s1 == cache.snapshot());
        leafA.tick();
        Leaf s2 = cache.snapshot();
        assertTrue(s1 != s2);
        assertTrue(leafA.get() == s1.get() + 1);
        assertTrue(leafA.get() == s2.get());

        // Test sealed snapshots
        SnapshotCache<Leaf> sealed = new SnapshotCache.Sealed();
        try {
            Leaf x1 = sealed.snapshot();
            fail(name + " sealed snapshot did not throw");
        } catch (UnsupportedOperationException e) {
            // This is the passing scenario - the exception is expected.
        }
    }
}
