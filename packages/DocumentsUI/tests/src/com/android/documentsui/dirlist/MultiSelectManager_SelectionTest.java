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

    private void assertContains(String id) {
        String err = String.format("Selection %s does not contain %s", selection, id);
        assertTrue(err, selection.contains(id));
    }
}
