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

import com.android.databinding.library.BaseObservable;
import com.android.databinding.testapp.generated.BasicBindingBinder;

import android.binding.Observable;
import android.binding.OnPropertyChangedListener;

import java.util.ArrayList;

public class BaseObservableTest extends BaseDataBinderTest<BasicBindingBinder> {
    private BaseObservable mObservable;
    private ArrayList<Integer> mNotifications = new ArrayList<>();
    private OnPropertyChangedListener mListener = new OnPropertyChangedListener() {
        @Override
        public void onPropertyChanged(Observable observable, int i) {
            assertEquals(mObservable, observable);
            mNotifications.add(i);
        }
    };

    public BaseObservableTest() {
        super(BasicBindingBinder.class, R.layout.basic_binding);
    }

    @Override
    protected void setUp() throws Exception {
        mNotifications.clear();
        mObservable = new BaseObservable();
    }

    public void testAddListener() {
        mObservable.notifyChange();
        assertTrue(mNotifications.isEmpty());
        mObservable.addOnPropertyChangedListener(mListener);
        mObservable.notifyChange();
        assertFalse(mNotifications.isEmpty());
    }

    public void testRemoveListener() {
        // test there is no exception when the listener isn't there
        mObservable.removeOnPropertyChangedListener(mListener);

        mObservable.addOnPropertyChangedListener(mListener);
        mObservable.notifyChange();
        mNotifications.clear();
        mObservable.removeOnPropertyChangedListener(mListener);
        mObservable.notifyChange();
        assertTrue(mNotifications.isEmpty());

        // test there is no exception when the listener isn't there
        mObservable.removeOnPropertyChangedListener(mListener);
    }

    public void testNotifyChange() {
        mObservable.addOnPropertyChangedListener(mListener);
        mObservable.notifyChange();
        assertEquals(1, mNotifications.size());
        assertEquals(0, (int) mNotifications.get(0));
    }

    public void testNotifyPropertyChanged() {
        final int expectedId = 100;
        mObservable.addOnPropertyChangedListener(mListener);
        mObservable.notifyPropertyChanged(expectedId);
        assertEquals(1, mNotifications.size());
        assertEquals(expectedId, (int) mNotifications.get(0));
    }
}
