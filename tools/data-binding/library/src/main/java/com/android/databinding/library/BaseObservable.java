/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.databinding.library;

import android.binding.Bindable;
import android.binding.Observable;
import android.binding.OnPropertyChangedListener;

public class BaseObservable implements Observable {
    private PropertyChangeRegistry mCallbacks;

    public BaseObservable() {
    }

    @Override
    public synchronized void addOnPropertyChangedListener(OnPropertyChangedListener listener) {
        if (mCallbacks == null) {
            mCallbacks = new PropertyChangeRegistry();
        }
        mCallbacks.add(listener);
    }

    @Override
    public synchronized void removeOnPropertyChangedListener(OnPropertyChangedListener listener) {
        if (mCallbacks != null) {
            mCallbacks.remove(listener);
        }
    }

    public synchronized void notifyChange() {
        if (mCallbacks != null) {
            mCallbacks.notifyCallbacks(this, 0, null);
        }
    }

    public void notifyPropertyChanged(int fieldId) {
        if (mCallbacks != null) {
            mCallbacks.notifyCallbacks(this, fieldId, null);
        }
    }
}
