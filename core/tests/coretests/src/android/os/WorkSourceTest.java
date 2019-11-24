/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os;

import android.os.WorkSource.WorkChain;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides unit tests for hidden / unstable WorkSource APIs that are not CTS testable.
 *
 * These tests will be moved to CTS when finalized.
 */
public class WorkSourceTest extends TestCase {
    public void testWorkChain_add() {
        WorkChain wc1 = new WorkChain();
        wc1.addNode(56, null);

        assertEquals(56, wc1.getUids()[0]);
        assertEquals(null, wc1.getTags()[0]);
        assertEquals(1, wc1.getSize());

        wc1.addNode(57, "foo");
        assertEquals(56, wc1.getUids()[0]);
        assertEquals(null, wc1.getTags()[0]);
        assertEquals(57, wc1.getUids()[1]);
        assertEquals("foo", wc1.getTags()[1]);

        assertEquals(2, wc1.getSize());
    }

    public void testWorkChain_equalsHashCode() {
        WorkChain wc1 = new WorkChain();
        WorkChain wc2 = new WorkChain();

        assertEquals(wc1, wc2);
        assertEquals(wc1.hashCode(), wc2.hashCode());

        wc1.addNode(1, null);
        wc2.addNode(1, null);
        assertEquals(wc1, wc2);
        assertEquals(wc1.hashCode(), wc2.hashCode());

        wc1.addNode(2, "tag");
        wc2.addNode(2, "tag");
        assertEquals(wc1, wc2);
        assertEquals(wc1.hashCode(), wc2.hashCode());

        wc1 = new WorkChain();
        wc2 = new WorkChain();
        wc1.addNode(5, null);
        wc2.addNode(6, null);
        assertFalse(wc1.equals(wc2));
        assertFalse(wc1.hashCode() == wc2.hashCode());

        wc1 = new WorkChain();
        wc2 = new WorkChain();
        wc1.addNode(5, "tag1");
        wc2.addNode(5, "tag2");
        assertFalse(wc1.equals(wc2));
        assertFalse(wc1.hashCode() == wc2.hashCode());
    }

    public void testWorkChain_constructor() {
        WorkChain wc1 = new WorkChain();
        wc1.addNode(1, "foo")
            .addNode(2, null)
            .addNode(3, "baz");

        WorkChain wc2 = new WorkChain(wc1);
        assertEquals(wc1, wc2);

        wc1.addNode(4, "baz");
        assertFalse(wc1.equals(wc2));
    }

    public void testDiff_workChains() {
        WorkSource ws1 = new WorkSource();
        ws1.add(50);
        ws1.createWorkChain().addNode(52, "foo");
        WorkSource ws2 = new WorkSource();
        ws2.add(50);
        ws2.createWorkChain().addNode(60, "bar");

        // Diffs don't take WorkChains into account for the sake of backward compatibility.
        assertFalse(ws1.diff(ws2));
        assertFalse(ws2.diff(ws1));
    }

    public void testEquals_workChains() {
        WorkSource ws1 = new WorkSource();
        ws1.add(50);
        ws1.createWorkChain().addNode(52, "foo");

        WorkSource ws2 = new WorkSource();
        ws2.add(50);
        ws2.createWorkChain().addNode(52, "foo");

        assertEquals(ws1, ws2);

        // Unequal number of WorkChains.
        ws2.createWorkChain().addNode(53, "baz");
        assertFalse(ws1.equals(ws2));

        // Different WorkChain contents.
        WorkSource ws3 = new WorkSource();
        ws3.add(50);
        ws3.createWorkChain().addNode(60, "bar");

        assertFalse(ws1.equals(ws3));
        assertFalse(ws3.equals(ws1));
    }

    public void testEquals_workChains_nullEmptyAreEquivalent() {
        // Construct a WorkSource that has no WorkChains, but whose workChains list
        // is non-null.
        WorkSource ws1 = new WorkSource();
        ws1.add(100);
        ws1.createWorkChain().addNode(100, null);
        ws1.getWorkChains().clear();

        WorkSource ws2 = new WorkSource();
        ws2.add(100);

        assertEquals(ws1, ws2);

        ws2.createWorkChain().addNode(100, null);
        assertFalse(ws1.equals(ws2));
    }

    public void testWorkSourceParcelling() {
        WorkSource ws = new WorkSource();

        WorkChain wc = ws.createWorkChain();
        wc.addNode(56, "foo");
        wc.addNode(75, "baz");
        WorkChain wc2 = ws.createWorkChain();
        wc2.addNode(20, "foo2");
        wc2.addNode(30, "baz2");

        Parcel p = Parcel.obtain();
        ws.writeToParcel(p, 0);
        p.setDataPosition(0);

        WorkSource unparcelled = WorkSource.CREATOR.createFromParcel(p);

        assertEquals(unparcelled, ws);
    }

    public void testSet_workChains() {
        WorkSource ws1 = new WorkSource();
        ws1.add(50);

        WorkSource ws2 = new WorkSource();
        ws2.add(60);
        WorkChain wc = ws2.createWorkChain();
        wc.addNode(75, "tag");

        ws1.set(ws2);

        // Assert that the WorkChains are copied across correctly to the new WorkSource object.
        List<WorkChain> workChains = ws1.getWorkChains();
        assertEquals(1, workChains.size());

        assertEquals(1, workChains.get(0).getSize());
        assertEquals(75, workChains.get(0).getUids()[0]);
        assertEquals("tag", workChains.get(0).getTags()[0]);

        // Also assert that a deep copy of workchains is made, so the addition of a new WorkChain
        // or the modification of an existing WorkChain has no effect.
        ws2.createWorkChain();
        assertEquals(1, ws1.getWorkChains().size());

        wc.addNode(50, "tag2");
        assertEquals(1, ws1.getWorkChains().size());
        assertEquals(1, ws1.getWorkChains().get(0).getSize());
    }

    public void testSet_nullWorkChain() {
        WorkSource ws = new WorkSource();
        ws.add(60);
        WorkChain wc = ws.createWorkChain();
        wc.addNode(75, "tag");

        ws.set(null);
        assertEquals(0, ws.getWorkChains().size());
    }

    public void testAdd_workChains() {
        WorkSource ws = new WorkSource();
        ws.createWorkChain().addNode(70, "foo");

        WorkSource ws2 = new WorkSource();
        ws2.createWorkChain().addNode(60, "tag");

        ws.add(ws2);

        // Check that the new WorkChain is added to the end of the list.
        List<WorkChain> workChains = ws.getWorkChains();
        assertEquals(2, workChains.size());
        assertEquals(1, workChains.get(1).getSize());
        assertEquals(60, ws.getWorkChains().get(1).getUids()[0]);
        assertEquals("tag", ws.getWorkChains().get(1).getTags()[0]);

        // Adding the same WorkChain twice should be a no-op.
        ws.add(ws2);
        assertEquals(2, workChains.size());
    }

    public void testSet_noWorkChains() {
        WorkSource ws = new WorkSource();
        ws.set(10);
        assertEquals(1, ws.size());
        assertEquals(10, ws.getUid(0));

        WorkSource ws2 = new WorkSource();
        ws2.set(20, "foo");
        assertEquals(1, ws2.size());
        assertEquals(20, ws2.getUid(0));
        assertEquals("foo", ws2.getPackageName(0));
    }

    public void testDiffChains_noChanges() {
        // WorkSources with no chains.
        assertEquals(null, WorkSource.diffChains(new WorkSource(), new WorkSource()));

        // WorkSources with the same chains.
        WorkSource ws1 = new WorkSource();
        ws1.createWorkChain().addNode(50, "tag");
        ws1.createWorkChain().addNode(60, "tag2");

        WorkSource ws2 = new WorkSource();
        ws2.createWorkChain().addNode(50, "tag");
        ws2.createWorkChain().addNode(60, "tag2");

        assertEquals(null, WorkSource.diffChains(ws1, ws1));
        assertEquals(null, WorkSource.diffChains(ws2, ws1));
    }

    public void testDiffChains_noChains() {
        // Diffs against a worksource with no chains.
        WorkSource ws1 = new WorkSource();
        WorkSource ws2 = new WorkSource();
        ws2.createWorkChain().addNode(70, "tag");
        ws2.createWorkChain().addNode(60, "tag2");

        // The "old" work source has no chains, so "newChains" should be non-null.
        ArrayList<WorkChain>[] diffs = WorkSource.diffChains(ws1, ws2);
        assertNotNull(diffs[0]);
        assertNull(diffs[1]);
        assertEquals(2, diffs[0].size());
        assertEquals(ws2.getWorkChains(), diffs[0]);

        // The "new" work source has no chains, so "oldChains" should be non-null.
        diffs = WorkSource.diffChains(ws2, ws1);
        assertNull(diffs[0]);
        assertNotNull(diffs[1]);
        assertEquals(2, diffs[1].size());
        assertEquals(ws2.getWorkChains(), diffs[1]);
    }

    public void testDiffChains_onlyAdditionsOrRemovals() {
        WorkSource ws1 = new WorkSource();
        WorkSource ws2 = new WorkSource();
        ws2.createWorkChain().addNode(70, "tag");
        ws2.createWorkChain().addNode(60, "tag2");

        // Both work sources have WorkChains : test the case where changes were only added
        // or were only removed.
        ws1.createWorkChain().addNode(70, "tag");

        // The "new" work source only contains additions (60, "tag2") in this case.
        ArrayList<WorkChain>[] diffs = WorkSource.diffChains(ws1, ws2);
        assertNotNull(diffs[0]);
        assertNull(diffs[1]);
        assertEquals(1, diffs[0].size());
        assertEquals(new WorkChain().addNode(60, "tag2"), diffs[0].get(0));

        // The "new" work source only contains removals (60, "tag2") in this case.
        diffs = WorkSource.diffChains(ws2, ws1);
        assertNull(diffs[0]);
        assertNotNull(diffs[1]);
        assertEquals(1, diffs[1].size());
        assertEquals(new WorkChain().addNode(60, "tag2"), diffs[1].get(0));
    }


    public void testDiffChains_generalCase() {
        WorkSource ws1 = new WorkSource();
        WorkSource ws2 = new WorkSource();

        // Both work sources have WorkChains, test the case where chains were added AND removed.
        ws1.createWorkChain().addNode(0, "tag0");
        ws2.createWorkChain().addNode(0, "tag0_changed");
        ArrayList<WorkChain>[] diffs = WorkSource.diffChains(ws1, ws2);
        assertNotNull(diffs[0]);
        assertNotNull(diffs[1]);
        assertEquals(ws2.getWorkChains(), diffs[0]);
        assertEquals(ws1.getWorkChains(), diffs[1]);

        // Give both WorkSources a chain in common; it should not be a part of any diffs.
        ws1.createWorkChain().addNode(1, "tag1");
        ws2.createWorkChain().addNode(1, "tag1");
        diffs = WorkSource.diffChains(ws1, ws2);
        assertNotNull(diffs[0]);
        assertNotNull(diffs[1]);
        assertEquals(1, diffs[0].size());
        assertEquals(1, diffs[1].size());
        assertEquals(new WorkChain().addNode(0, "tag0_changed"), diffs[0].get(0));
        assertEquals(new WorkChain().addNode(0, "tag0"), diffs[1].get(0));

        // Finally, test the case where more than one chain was added / removed.
        ws1.createWorkChain().addNode(2, "tag2");
        ws2.createWorkChain().addNode(2, "tag2_changed");
        diffs = WorkSource.diffChains(ws1, ws2);
        assertNotNull(diffs[0]);
        assertNotNull(diffs[1]);
        assertEquals(2, diffs[0].size());
        assertEquals(2, diffs[1].size());
        assertEquals(new WorkChain().addNode(0, "tag0_changed"), diffs[0].get(0));
        assertEquals(new WorkChain().addNode(2, "tag2_changed"), diffs[0].get(1));
        assertEquals(new WorkChain().addNode(0, "tag0"), diffs[1].get(0));
        assertEquals(new WorkChain().addNode(2, "tag2"), diffs[1].get(1));
    }

    public void testGetAttributionId() {
        WorkSource ws = new WorkSource();
        WorkChain wc1 = ws.createWorkChain();
        wc1.addNode(100, "tag");
        assertEquals(100, wc1.getAttributionUid());
        assertEquals(100, ws.getAttributionUid());
        wc1.addNode(200, "tag2");
        assertEquals(100, wc1.getAttributionUid());
        assertEquals(100, ws.getAttributionUid());
        WorkChain wc2 = ws.createWorkChain();
        wc2.addNode(300, "tag3");
        assertEquals(300, wc2.getAttributionUid());
        assertEquals(100, ws.getAttributionUid());
    }

    public void testGetAttributionIdWithoutWorkChain() {
        WorkSource ws1 = new WorkSource(100);
        ws1.add(200);
        WorkSource ws2 = new WorkSource();
        ws2.add(100);
        ws2.add(200);
        assertEquals(100, ws1.getAttributionUid());
        assertEquals(100, ws2.getAttributionUid());
    }

    public void testGetAttributionWhenEmpty() {
        WorkSource ws = new WorkSource();
        assertEquals(-1, ws.getAttributionUid());
        WorkChain wc = ws.createWorkChain();
        assertEquals(-1, ws.getAttributionUid());
        assertEquals(-1, wc.getAttributionUid());
        assertNull(wc.getAttributionTag());
    }

    public void testGetAttributionTag() {
        WorkSource ws1 = new WorkSource();
        WorkChain wc = ws1.createWorkChain();
        wc.addNode(100, "tag");
        assertEquals("tag", wc.getAttributionTag());
        wc.addNode(200, "tag2");
        assertEquals("tag", wc.getAttributionTag());
    }

    public void testRemove_fromChainedWorkSource() {
        WorkSource ws1 = new WorkSource();
        ws1.createWorkChain().addNode(50, "foo");
        ws1.createWorkChain().addNode(75, "bar");
        ws1.add(100);

        WorkSource ws2 = new WorkSource();
        ws2.add(100);

        assertTrue(ws1.remove(ws2));
        assertEquals(2, ws1.getWorkChains().size());
        assertEquals(50, ws1.getWorkChains().get(0).getAttributionUid());
        assertEquals(75, ws1.getWorkChains().get(1).getAttributionUid());

        ws2.createWorkChain().addNode(50, "foo");
        assertTrue(ws1.remove(ws2));
        assertEquals(1, ws1.getWorkChains().size());
        assertEquals(75, ws1.getWorkChains().get(0).getAttributionUid());
    }

    public void testRemove_fromSameWorkSource() {
        WorkSource ws1 = new WorkSource(50, "foo");
        WorkSource ws2 = ws1;
        ws2.add(ws1);
        assertTrue(ws2.remove(ws1));

        assertEquals(0, ws1.size());
        assertEquals(50, ws1.getUid(0));
        assertEquals("foo", ws1.getPackageName(0));
    }

    public void testTransferWorkChains() {
        WorkSource ws1 = new WorkSource();
        WorkChain wc1 = ws1.createWorkChain().addNode(100, "tag");
        WorkChain wc2 = ws1.createWorkChain().addNode(200, "tag2");

        WorkSource ws2 = new WorkSource();
        ws2.transferWorkChains(ws1);

        assertEquals(0, ws1.getWorkChains().size());
        assertEquals(2, ws2.getWorkChains().size());
        assertSame(wc1, ws2.getWorkChains().get(0));
        assertSame(wc2, ws2.getWorkChains().get(1));

        ws1.clear();
        ws1.createWorkChain().addNode(300, "tag3");
        ws1.transferWorkChains(ws2);
        assertEquals(0, ws2.getWorkChains().size());
        assertSame(wc1, ws1.getWorkChains().get(0));
        assertSame(wc2, ws1.getWorkChains().get(1));
    }
}
