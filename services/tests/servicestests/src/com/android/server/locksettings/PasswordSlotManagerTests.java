/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.locksettings;

import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PasswordSlotManagerTests extends AndroidTestCase {

    PasswordSlotManagerTestable mManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mManager = new PasswordSlotManagerTestable();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        mManager.cleanup();
    }

    public void testBasicSlotUse() throws Exception {
        mManager.markSlotInUse(0);
        mManager.markSlotInUse(1);

        Set<Integer> expected = new HashSet<Integer>();
        expected.add(0);
        expected.add(1);
        assertEquals(expected, mManager.getUsedSlots());

        mManager.markSlotDeleted(1);

        expected = new HashSet<Integer>();
        expected.add(0);
        assertEquals(expected, mManager.getUsedSlots());
    }

    public void testMergeSlots() throws Exception {
        // Add some slots from a different OS image.
        mManager.setGsiImageNumber(1);
        mManager.markSlotInUse(4);
        mManager.markSlotInUse(6);

        // Switch back to the host image.
        mManager.setGsiImageNumber(0);
        mManager.markSlotInUse(0);
        mManager.markSlotInUse(3);
        mManager.markSlotInUse(5);

        // Correct slot information for the host image.
        Set<Integer> actual = new HashSet<Integer>();
        actual.add(1);
        actual.add(3);
        mManager.refreshActiveSlots(actual);

        Set<Integer> expected = new HashSet<Integer>();
        expected.add(1);
        expected.add(3);
        expected.add(4);
        expected.add(6);
        assertEquals(expected, mManager.getUsedSlots());
    }

    public void testSerialization() throws Exception {
        mManager.markSlotInUse(0);
        mManager.markSlotInUse(1);
        mManager.setGsiImageNumber(1);
        mManager.markSlotInUse(4);

        final ByteArrayOutputStream saved = new ByteArrayOutputStream();
        mManager.saveSlotMap(saved);

        final HashMap<Integer, String> expected = new HashMap<Integer, String>();
        expected.put(0, "host");
        expected.put(1, "host");
        expected.put(4, "gsi1");

        final Map<Integer, String> map = mManager.loadSlotMap(
                new ByteArrayInputStream(saved.toByteArray()));
        assertEquals(expected, map);
    }

    public void testSaving() throws Exception {
        mManager.markSlotInUse(0);
        mManager.markSlotInUse(1);
        mManager.setGsiImageNumber(1);
        mManager.markSlotInUse(4);

        // Make a new one. It should load the previous map.
        mManager = new PasswordSlotManagerTestable();

        Set<Integer> expected = new HashSet<Integer>();
        expected.add(0);
        expected.add(1);
        expected.add(4);
        assertEquals(expected, mManager.getUsedSlots());
    }
}
