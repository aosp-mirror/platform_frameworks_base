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

import com.android.databinding.library.MapChangeRegistry;
import com.android.databinding.library.ObservableArrayMap;
import com.android.databinding.testapp.generated.BasicBindingBinder;

import android.binding.ObservableMap;
import android.binding.OnMapChangedListener;

public class MapChangeRegistryTest extends BaseDataBinderTest<BasicBindingBinder> {

    private int notificationCount = 0;

    public MapChangeRegistryTest() {
        super(BasicBindingBinder.class, R.layout.basic_binding);
    }

    public void testNotifyAllChanged() {
        MapChangeRegistry mapChangeRegistry = new MapChangeRegistry();

        final ObservableMap<String, Integer> observableObj = new ObservableArrayMap<>();

        final String expectedKey = "key";
        OnMapChangedListener listener = new OnMapChangedListener<ObservableMap<String, Integer>, String>() {
            @Override
            public void onMapChanged(ObservableMap sender, String key) {
                notificationCount++;
                assertEquals(observableObj, sender);
                assertEquals(key, expectedKey);
            }
        };
        mapChangeRegistry.add(listener);

        assertEquals(0, notificationCount);
        mapChangeRegistry.notifyChange(observableObj, expectedKey);
        assertEquals(1, notificationCount);
    }
}
