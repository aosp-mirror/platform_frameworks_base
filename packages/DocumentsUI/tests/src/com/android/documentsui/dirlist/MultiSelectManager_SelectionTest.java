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

package com.android.documentsui.dirlist;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.google.common.collect.Sets;

import java.util.HashSet;
import java.util.Set;

@SmallTest
public class MultiSelectManager_SelectionTest extends AndroidTestCase {

    private Selection selection;

    private String[] ids = new String[]{
            "foo",
            "43",
            "auth|id=@53di*/f3#d"
    };

    @Override
    public void setUp() throws Exception {
        selection = new Selection();
        selection.add(ids[0]);
        selection.add(ids[1]);
        selection.add(ids[2]);
    }

    public void testAdd() {
        // We added in setUp.
        assertEquals(3, selection.size());
        assertContains(ids[0]);
        assertContains(ids[1]);
        assertContains(ids[2]);
    }

    public void testRemove() {
        selection.remove(ids[0]);
        selection.remove(ids[2]);
        assertEquals(1, selection.size());
        assertContains(ids[1]);
    }

    public void testClear() {
        selection.clear();
        assertEquals(0, selection.size());
    }

    public void testIsEmpty() {
        assertTrue(new Selection().isEmpty());
        selection.clear();
        assertTrue(selection.isEmpty());
    }

    public void testSize() {
        Selection other = new Selection();
        for (int i = 0; i < selection.size(); i++) {
            other.add(ids[i]);
        }
        assertEquals(selection.size(), other.size());
    }

    public void testEqualsSelf() {
        assertEquals(selection, selection);
    }

    public void testEqualsOther() {
        Selection other = new Selection();
        other.add(ids[0]);
        other.add(ids[1]);
        other.add(ids[2]);
        assertEquals(selection, other);
        assertEquals(selection.hashCode(), other.hashCode());
    }

    public void testEqualsCopy() {
        Selection other = new Selection();
        other.copyFrom(selection);
        assertEquals(selection, other);
        assertEquals(selection.hashCode(), other.hashCode());
    }

    public void testNotEquals() {
        Selection other = new Selection();
        other.add("foobar");
        assertFalse(selection.equals(other));
    }

    public void testIntersection_empty0() {
        Selection testSelection = new Selection();
        testSelection.intersect(new HashSet<String>());
        assertTrue(testSelection.isEmpty());
    }

    public void testIntersection_empty1() {
        Selection testSelection = new Selection();
        testSelection.intersect(Sets.newHashSet("foo"));
        assertTrue(testSelection.isEmpty());
    }

    public void testIntersection_empty2() {
        assertFalse(selection.isEmpty());
        selection.intersect(new HashSet<String>());
        assertTrue(selection.isEmpty());
    }

    public void testIntersection_exclusive() {
        String[] ids0 = new String[]{"foo", "bar", "baz"};
        String[] ids1 = new String[]{"0", "1", "2"};

        Selection testSelection = new Selection();
        testSelection.add(ids0[0]);
        testSelection.add(ids0[1]);
        testSelection.add(ids0[2]);

        Set<String> set = Sets.newHashSet(ids1);
        testSelection.intersect(set);

        assertTrue(testSelection.isEmpty());
    }

    public void testIntersection_subset() {
        String[] ids0 = new String[]{"foo", "bar", "baz"};
        String[] ids1 = new String[]{"0", "baz", "1", "foo", "2"};

        Selection testSelection = new Selection();
        testSelection.add(ids0[0]);
        testSelection.add(ids0[1]);
        testSelection.add(ids0[2]);

        testSelection.intersect(Sets.newHashSet(ids1));

        assertTrue(testSelection.contains("foo"));
        assertFalse(testSelection.contains("bar"));
        assertTrue(testSelection.contains("baz"));
    }

    public void testIntersection_all() {
        String[] ids0 = new String[]{"foo", "bar", "baz"};
        String[] ids1 = new String[]{"0", "baz", "1", "foo", "2", "bar"};

        Selection testSelection = new Selection();
        testSelection.add(ids0[0]);
        testSelection.add(ids0[1]);
        testSelection.add(ids0[2]);

        Selection control = new Selection();
        control.copyFrom(testSelection);

        testSelection.intersect(Sets.newHashSet(ids1));

        assertTrue(testSelection.equals(control));
    }

    private void assertContains(String id) {
        String err = String.format("Selection %s does not contain %s", selection, id);
        assertTrue(err, selection.contains(id));
    }
}
