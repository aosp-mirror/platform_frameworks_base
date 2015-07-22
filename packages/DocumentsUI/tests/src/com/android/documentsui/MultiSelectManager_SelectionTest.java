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

package com.android.documentsui;

import static org.junit.Assert.*;

import com.android.documentsui.MultiSelectManager.Selection;

import org.junit.Before;
import org.junit.Test;

public class MultiSelectManager_SelectionTest {

    private Selection selection;

    @Before
    public void setUp() throws Exception {
        selection = new Selection();
        selection.add(3);
        selection.add(5);
        selection.add(9);
    }

    @Test
    public void add() {
        // We added in setUp.
        assertEquals(3, selection.size());
        assertContains(3);
        assertContains(5);
        assertContains(9);
    }

    @Test
    public void remove() {
        selection.remove(3);
        selection.remove(5);
        assertEquals(1, selection.size());
        assertContains(9);
    }

    @Test
    public void clear() {
        selection.clear();
        assertEquals(0, selection.size());
    }

    @Test
    public void sizeAndGet() {
        Selection other = new Selection();
        for (int i = 0; i < selection.size(); i++) {
            other.add(selection.get(i));
        }
        assertEquals(selection.size(), other.size());
    }

    @Test
    public void equalsSelf() {
        assertEquals(selection, selection);
    }

    @Test
    public void equalsOther() {
        Selection other = new Selection();
        other.add(3);
        other.add(5);
        other.add(9);
        assertEquals(selection, other);
        assertEquals(selection.hashCode(), other.hashCode());
    }

    @Test
    public void equalsCopy() {
        Selection other = new Selection();
        other.copyFrom(selection);
        assertEquals(selection, other);
        assertEquals(selection.hashCode(), other.hashCode());
    }

    @Test
    public void notEquals() {
        Selection other = new Selection();
        other.add(789);
        assertFalse(selection.equals(other));
    }

    @Test
    public void expandBefore() {
        selection.expand(2, 10);
        assertEquals(3, selection.size());
        assertContains(13);
        assertContains(15);
        assertContains(19);
    }

    @Test
    public void expandAfter() {
        selection.expand(10, 10);
        assertEquals(3, selection.size());
        assertContains(3);
        assertContains(5);
        assertContains(9);
    }

    @Test
    public void expandSplit() {
        selection.expand(5, 10);
        assertEquals(3, selection.size());
        assertContains(3);
        assertContains(15);
        assertContains(19);
    }

    @Test
    public void expandEncompased() {
        selection.expand(2, 10);
        assertEquals(3, selection.size());
        assertContains(13);
        assertContains(15);
        assertContains(19);
    }

    @Test
    public void collapseBefore() {
        selection.collapse(0, 2);
        assertEquals(3, selection.size());
        assertContains(1);
        assertContains(3);
        assertContains(7);
    }

    @Test
    public void collapseAfter() {
        selection.collapse(10, 10);
        assertEquals(3, selection.size());
        assertContains(3);
        assertContains(5);
        assertContains(9);
    }

    @Test
    public void collapseAcross() {
        selection.collapse(0, 10);
        assertEquals(0, selection.size());
    }

    private void assertContains(int i) {
        String err = String.format("Selection %s does not contain %d", selection, i);
        assertTrue(err, selection.contains(i));
    }
}
