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

import com.android.databinding.library.ObservableArrayMap;
import com.android.databinding.testapp.generated.BasicBindingBinder;

import android.binding.ObservableMap;
import android.binding.OnMapChangedListener;
import android.support.v4.util.ArrayMap;
import android.support.v4.util.SimpleArrayMap;

import java.util.ArrayList;
import java.util.Map;

public class ObservableArrayMapTest extends BaseDataBinderTest<BasicBindingBinder> {

    private ObservableArrayMap<String, String> mObservable;

    private ArrayList<String> mNotifications = new ArrayList<>();

    private OnMapChangedListener mListener = new OnMapChangedListener() {
        @Override
        public void onMapChanged(ObservableMap observableMap, Object o) {
            assertEquals(mObservable, observableMap);
            mNotifications.add((String) o);
        }
    };

    public ObservableArrayMapTest() {
        super(BasicBindingBinder.class, R.layout.basic_binding);
    }

    @Override
    protected void setUp() throws Exception {
        mNotifications.clear();
        mObservable = new ObservableArrayMap<>();
    }

    public void testAddListener() {
        mObservable.put("Hello", "World");
        assertTrue(mNotifications.isEmpty());
        mObservable.addOnMapChangedListener(mListener);
        mObservable.put("Hello", "Goodbye");
        assertFalse(mNotifications.isEmpty());
    }

    public void testRemoveListener() {
        // test there is no exception when the listener isn't there
        mObservable.removeOnMapChangedListener(mListener);

        mObservable.addOnMapChangedListener(mListener);
        mObservable.put("Hello", "World");
        mNotifications.clear();
        mObservable.removeOnMapChangedListener(mListener);
        mObservable.put("World", "Hello");
        assertTrue(mNotifications.isEmpty());

        // test there is no exception when the listener isn't there
        mObservable.removeOnMapChangedListener(mListener);
    }

    public void testClear() {
        mObservable.put("Hello", "World");
        mObservable.put("World", "Hello");
        mObservable.addOnMapChangedListener(mListener);
        mObservable.clear();
        assertEquals(1, mNotifications.size());
        assertNull(mNotifications.get(0));
        assertEquals(0, mObservable.size());
        assertTrue(mObservable.isEmpty());

        mObservable.clear();
        // No notification when nothing is cleared.
        assertEquals(1, mNotifications.size());
    }

    public void testPut() {
        mObservable.addOnMapChangedListener(mListener);
        mObservable.put("Hello", "World");
        assertEquals(1, mNotifications.size());
        assertEquals("Hello", mNotifications.get(0));
        assertEquals("World", mObservable.get("Hello"));

        mObservable.put("Hello", "World2");
        assertEquals(2, mNotifications.size());
        assertEquals("Hello", mNotifications.get(1));
        assertEquals("World2", mObservable.get("Hello"));

        mObservable.put("World", "Hello");
        assertEquals(3, mNotifications.size());
        assertEquals("World", mNotifications.get(2));
        assertEquals("Hello", mObservable.get("World"));
    }

    public void testPutAll() {
        Map<String, String> toAdd = new ArrayMap<>();
        toAdd.put("Hello", "World");
        toAdd.put("Goodbye", "Cruel World");
        mObservable.put("Cruel", "World");
        mObservable.addOnMapChangedListener(mListener);
        mObservable.putAll(toAdd);
        assertEquals(3, mObservable.size());
        assertEquals("World", mObservable.get("Hello"));
        assertEquals("Cruel World", mObservable.get("Goodbye"));
        assertEquals(2, mNotifications.size());
        // order is not guaranteed
        assertTrue(mNotifications.contains("Hello"));
        assertTrue(mNotifications.contains("Goodbye"));
    }

    public void testPutAllSimpleArrayMap() {
        SimpleArrayMap<String, String> toAdd = new ArrayMap<>();
        toAdd.put("Hello", "World");
        toAdd.put("Goodbye", "Cruel World");
        mObservable.put("Cruel", "World");
        mObservable.addOnMapChangedListener(mListener);
        mObservable.putAll(toAdd);
        assertEquals(3, mObservable.size());
        assertEquals("World", mObservable.get("Hello"));
        assertEquals("Cruel World", mObservable.get("Goodbye"));
        assertEquals(2, mNotifications.size());
        // order is not guaranteed
        assertTrue(mNotifications.contains("Hello"));
        assertTrue(mNotifications.contains("Goodbye"));
    }

    public void testRemove() {
        mObservable.put("Hello", "World");
        mObservable.put("Goodbye", "Cruel World");
        mObservable.addOnMapChangedListener(mListener);
        assertEquals("World", mObservable.remove("Hello"));
        assertEquals(1, mNotifications.size());
        assertEquals("Hello", mNotifications.get(0));

        assertNull(mObservable.remove("Hello"));
        // nothing removed, don't notify
        assertEquals(1, mNotifications.size());
    }

    public void testRemoveAll() {
        ArrayList<String> toRemove = new ArrayList<>();
        toRemove.add("Hello");
        toRemove.add("Goodbye");
        mObservable.put("Hello", "World");
        mObservable.put("Goodbye", "Cruel World");
        mObservable.put("Cruel", "World");
        mObservable.addOnMapChangedListener(mListener);
        assertTrue(mObservable.removeAll(toRemove));
        assertEquals(2, mNotifications.size());
        // order is not guaranteed
        assertTrue(mNotifications.contains("Hello"));
        assertTrue(mNotifications.contains("Goodbye"));

        assertTrue(mObservable.containsKey("Cruel"));

        // Test nothing removed
        assertFalse(mObservable.removeAll(toRemove));
        assertEquals(2, mNotifications.size());
    }

    public void testRetainAll() {
        ArrayList<String> toRetain = new ArrayList<>();
        toRetain.add("Hello");
        toRetain.add("Goodbye");
        mObservable.put("Hello", "World");
        mObservable.put("Goodbye", "Cruel World");
        mObservable.put("Cruel", "World");
        mObservable.addOnMapChangedListener(mListener);
        assertTrue(mObservable.retainAll(toRetain));
        assertEquals(1, mNotifications.size());
        assertEquals("Cruel", mNotifications.get(0));
        assertTrue(mObservable.containsKey("Hello"));
        assertTrue(mObservable.containsKey("Goodbye"));

        // Test nothing removed
        assertFalse(mObservable.retainAll(toRetain));
        assertEquals(1, mNotifications.size());
    }

    public void testRemoveAt() {
        mObservable.put("Hello", "World");
        mObservable.put("Goodbye", "Cruel World");
        mObservable.addOnMapChangedListener(mListener);
        String key = mObservable.keyAt(0);
        String value = mObservable.valueAt(0);
        assertTrue("Hello".equals(key) || "Goodbye".equals(key));
        assertEquals(value, mObservable.removeAt(0));
        assertEquals(1, mNotifications.size());
        assertEquals(key, mNotifications.get(0));
    }

    public void testSetValueAt() {
        mObservable.put("Hello", "World");
        mObservable.addOnMapChangedListener(mListener);
        assertEquals("World", mObservable.setValueAt(0, "Cruel World"));
        assertEquals(1, mNotifications.size());
        assertEquals("Hello", mNotifications.get(0));
    }
}
