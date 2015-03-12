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
package com.android.databinding.testapp;

import com.android.databinding.library.ObservableArrayList;
import com.android.databinding.testapp.generated.BasicBindingBinder;

import android.binding.ObservableList;
import android.binding.OnListChangedListener;

import java.util.ArrayList;

public class ObservableArrayListTest extends BaseDataBinderTest<BasicBindingBinder> {

    private static final int ALL = 0;

    private static final int CHANGE = 1;

    private static final int INSERT = 2;

    private static final int MOVE = 3;

    private static final int REMOVE = 4;

    private ObservableList<String> mObservable;

    private ArrayList<ListChange> mNotifications = new ArrayList<>();

    private OnListChangedListener mListener = new OnListChangedListener() {
        @Override
        public void onChanged() {
            mNotifications.add(new ListChange(ALL, 0, 0));
        }

        @Override
        public void onItemRangeChanged(int start, int count) {
            mNotifications.add(new ListChange(CHANGE, start, count));
        }

        @Override
        public void onItemRangeInserted(int start, int count) {
            mNotifications.add(new ListChange(INSERT, start, count));
        }

        @Override
        public void onItemRangeMoved(int from, int to, int count) {
            mNotifications.add(new ListChange(MOVE, from, to, count));
        }

        @Override
        public void onItemRangeRemoved(int start, int count) {
            mNotifications.add(new ListChange(REMOVE, start, count));
        }
    };

    private static class ListChange {

        public ListChange(int change, int start, int count) {
            this.start = start;
            this.count = count;
            this.from = 0;
            this.to = 0;
            this.change = change;
        }

        public ListChange(int change, int from, int to, int count) {
            this.from = from;
            this.to = to;
            this.count = count;
            this.start = 0;
            this.change = change;
        }

        public final int start;

        public final int count;

        public final int from;

        public final int to;

        public final int change;
    }

    public ObservableArrayListTest() {
        super(BasicBindingBinder.class, R.layout.basic_binding);
    }

    @Override
    protected void setUp() throws Exception {
        mNotifications.clear();
        mObservable = new ObservableArrayList<>();
    }

    public void testAddListener() {
        mObservable.add("Hello");
        assertTrue(mNotifications.isEmpty());
        mObservable.addOnListChangedListener(mListener);
        mObservable.add("World");
        assertFalse(mNotifications.isEmpty());
    }

    public void testRemoveListener() {
        // test there is no exception when the listener isn't there
        mObservable.removeOnListChangedListener(mListener);

        mObservable.addOnListChangedListener(mListener);
        mObservable.add("Hello");
        mNotifications.clear();
        mObservable.removeOnListChangedListener(mListener);
        mObservable.add("World");
        assertTrue(mNotifications.isEmpty());

        // test there is no exception when the listener isn't there
        mObservable.removeOnListChangedListener(mListener);
    }

    public void testAdd() {
        mObservable.addOnListChangedListener(mListener);
        mObservable.add("Hello");
        assertEquals(1, mNotifications.size());
        ListChange change = mNotifications.get(0);
        assertEquals(INSERT, change.change);
        assertEquals(0, change.start);
        assertEquals(1, change.count);
        assertEquals("Hello", mObservable.get(0));
    }

    public void testInsert() {
        mObservable.addOnListChangedListener(mListener);
        mObservable.add("Hello");
        mObservable.add(0, "World");
        mObservable.add(1, "Dang");
        mObservable.add(3, "End");
        assertEquals(4, mObservable.size());
        assertEquals("World", mObservable.get(0));
        assertEquals("Dang", mObservable.get(1));
        assertEquals("Hello", mObservable.get(2));
        assertEquals("End", mObservable.get(3));
        assertEquals(4, mNotifications.size());
        ListChange change = mNotifications.get(1);
        assertEquals(INSERT, change.change);
        assertEquals(0, change.start);
        assertEquals(1, change.count);
    }

    public void testAddAll() {
        ArrayList<String> toAdd = new ArrayList<>();
        toAdd.add("Hello");
        toAdd.add("World");
        mObservable.add("First");
        mObservable.addOnListChangedListener(mListener);
        mObservable.addAll(toAdd);
        assertEquals(3, mObservable.size());
        assertEquals("Hello", mObservable.get(1));
        assertEquals("World", mObservable.get(2));
        assertEquals(1, mNotifications.size());
        ListChange change = mNotifications.get(0);
        assertEquals(INSERT, change.change);
        assertEquals(1, change.start);
        assertEquals(2, change.count);
    }

    public void testInsertAll() {
        ArrayList<String> toAdd = new ArrayList<>();
        toAdd.add("Hello");
        toAdd.add("World");
        mObservable.add("First");
        mObservable.addOnListChangedListener(mListener);
        mObservable.addAll(0, toAdd);
        assertEquals(3, mObservable.size());
        assertEquals("Hello", mObservable.get(0));
        assertEquals("World", mObservable.get(1));
        assertEquals(1, mNotifications.size());
        ListChange change = mNotifications.get(0);
        assertEquals(INSERT, change.change);
        assertEquals(0, change.start);
        assertEquals(2, change.count);
    }

    public void testClear() {
        mObservable.add("Hello");
        mObservable.add("World");
        mObservable.addOnListChangedListener(mListener);
        mObservable.clear();
        assertEquals(1, mNotifications.size());
        ListChange change = mNotifications.get(0);
        assertEquals(REMOVE, change.change);
        assertEquals(0, change.start);
        assertEquals(2, change.count);

        mObservable.clear();
        // No notification when nothing is cleared.
        assertEquals(1, mNotifications.size());
    }

    public void testRemoveIndex() {
        mObservable.add("Hello");
        mObservable.add("World");
        mObservable.addOnListChangedListener(mListener);
        assertEquals("Hello", mObservable.remove(0));
        assertEquals(1, mNotifications.size());
        ListChange change = mNotifications.get(0);
        assertEquals(REMOVE, change.change);
        assertEquals(0, change.start);
        assertEquals(1, change.count);
    }

    public void testRemoveObject() {
        mObservable.add("Hello");
        mObservable.add("World");
        mObservable.addOnListChangedListener(mListener);
        assertTrue(mObservable.remove("Hello"));
        assertEquals(1, mNotifications.size());
        ListChange change = mNotifications.get(0);
        assertEquals(REMOVE, change.change);
        assertEquals(0, change.start);
        assertEquals(1, change.count);

        assertFalse(mObservable.remove("Hello"));
        // nothing removed, don't notify
        assertEquals(1, mNotifications.size());
    }

    public void testSet() {
        mObservable.add("Hello");
        mObservable.add("World");
        mObservable.addOnListChangedListener(mListener);
        assertEquals("Hello", mObservable.set(0, "Goodbye"));
        assertEquals("Goodbye", mObservable.get(0));
        assertEquals(2, mObservable.size());
        ListChange change = mNotifications.get(0);
        assertEquals(CHANGE, change.change);
        assertEquals(0, change.start);
        assertEquals(1, change.count);
    }
}
