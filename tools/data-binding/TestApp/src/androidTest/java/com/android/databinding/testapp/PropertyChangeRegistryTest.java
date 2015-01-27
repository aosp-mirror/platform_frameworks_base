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

import com.android.databinding.library.PropertyChangeRegistry;
import com.android.databinding.testapp.generated.BasicBindingBinder;

import android.binding.Observable;
import android.binding.OnPropertyChangedListener;

public class PropertyChangeRegistryTest extends BaseDataBinderTest<BasicBindingBinder> {

    private int notificationCount = 0;

    public PropertyChangeRegistryTest() {
        super(BasicBindingBinder.class, R.layout.basic_binding);
    }

    public void testNotifyChanged() {
        PropertyChangeRegistry propertyChangeRegistry = new PropertyChangeRegistry();

        final Observable observableObj = new Observable() {
            @Override
            public void addOnPropertyChangedListener(
                    OnPropertyChangedListener onPropertyChangedListener) {
            }

            @Override
            public void removeOnPropertyChangedListener(
                    OnPropertyChangedListener onPropertyChangedListener) {
            }
        };

        final int expectedId = 100;
        OnPropertyChangedListener listener = new OnPropertyChangedListener() {
            @Override
            public void onPropertyChanged(Observable observable, int id) {
                notificationCount++;
                assertEquals(expectedId, id);
                assertEquals(observableObj, observable);
            }
        };
        propertyChangeRegistry.add(listener);

        assertEquals(0, notificationCount);
        propertyChangeRegistry.notifyChange(observableObj, expectedId);
        assertEquals(1, notificationCount);
    }
}
