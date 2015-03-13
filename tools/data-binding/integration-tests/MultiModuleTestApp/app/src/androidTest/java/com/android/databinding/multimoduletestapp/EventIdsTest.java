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

package com.android.databinding.multimoduletestapp;

import com.android.databinding.testlibrary.ObservableInLibrary;

import android.binding.Observable;
import android.binding.OnPropertyChangedListener;
import android.os.Debug;
import android.test.AndroidTestCase;

import java.util.HashMap;
import java.util.Map;

import com.android.databinding.multimoduletestapp.BR;

public class EventIdsTest extends AndroidTestCase {
    public void testLibraryObservable() {
        ObservableInLibrary observableInLibrary = new ObservableInLibrary();
        EventCounter ec = new EventCounter();
        observableInLibrary.addOnPropertyChangedListener(ec);
        ec.assertProperty(BR.libField1, 0);
        ec.assertProperty(BR.libField2, 0);
        ec.assertProperty(BR.sharedField, 0);

        observableInLibrary.setLibField1("a");
        ec.assertProperty(BR.libField1, 1);
        ec.assertProperty(BR.libField2, 0);
        ec.assertProperty(BR.sharedField, 0);

        observableInLibrary.setLibField2("b");
        ec.assertProperty(BR.libField1, 1);
        ec.assertProperty(BR.libField2, 1);
        ec.assertProperty(BR.sharedField, 0);

        observableInLibrary.setSharedField(3);
        ec.assertProperty(BR.libField1, 1);
        ec.assertProperty(BR.libField2, 1);
        ec.assertProperty(BR.sharedField, 1);
    }

    public void testAppObservable() {
        ObservableInMainApp observableInMainApp = new ObservableInMainApp();
        EventCounter ec = new EventCounter();
        observableInMainApp.addOnPropertyChangedListener(ec);
        ec.assertProperty(BR.appField1, 0);
        ec.assertProperty(BR.appField2, 0);
        ec.assertProperty(BR.sharedField, 0);

        observableInMainApp.setAppField2(3);
        ec.assertProperty(BR.appField1, 0);
        ec.assertProperty(BR.appField2, 1);
        ec.assertProperty(BR.sharedField, 0);

        observableInMainApp.setAppField1("b");
        ec.assertProperty(BR.appField1, 1);
        ec.assertProperty(BR.appField2, 1);
        ec.assertProperty(BR.sharedField, 0);

        observableInMainApp.setSharedField(5);
        ec.assertProperty(BR.appField1, 1);
        ec.assertProperty(BR.appField2, 1);
        ec.assertProperty(BR.sharedField, 1);
    }

    public void testExtendingObservable() {
        ObservableExtendingLib observable = new ObservableExtendingLib();
        EventCounter ec = new EventCounter();
        observable.addOnPropertyChangedListener(ec);

        ec.assertProperty(BR.childClassField, 0);
        ec.assertProperty(BR.libField1, 0);
        ec.assertProperty(BR.libField2, 0);
        ec.assertProperty(BR.sharedField, 0);

        observable.setChildClassField("a");
        ec.assertProperty(BR.childClassField, 1);
        ec.assertProperty(BR.libField1, 0);
        ec.assertProperty(BR.libField2, 0);
        ec.assertProperty(BR.sharedField, 0);

        observable.setLibField1("b");
        ec.assertProperty(BR.childClassField, 1);
        ec.assertProperty(BR.libField1, 1);
        ec.assertProperty(BR.libField2, 0);
        ec.assertProperty(BR.sharedField, 0);

        observable.setLibField2("c");
        ec.assertProperty(BR.childClassField, 1);
        ec.assertProperty(BR.libField1, 1);
        ec.assertProperty(BR.libField2, 1);
        ec.assertProperty(BR.sharedField, 0);

        observable.setSharedField(2);
        ec.assertProperty(BR.childClassField, 1);
        ec.assertProperty(BR.libField1, 1);
        ec.assertProperty(BR.libField2, 1);
        ec.assertProperty(BR.sharedField, 1);
    }

    private static class EventCounter implements OnPropertyChangedListener {
        Map<Integer, Integer> mCounter = new HashMap<>();

        @Override
        public void onPropertyChanged(Observable observable, int propertyId) {
            mCounter.put(propertyId, get(propertyId) + 1);
        }

        public int get(int propertyId) {
            Integer val = mCounter.get(propertyId);
            return val == null ? 0 : val;
        }

        private void assertProperty(int propertyId, int value) {
            assertEquals(get(propertyId), value);
        }
    }
}
