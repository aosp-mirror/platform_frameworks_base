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

package android.databinding.testlibrary;

import android.databinding.Bindable;

import android.databinding.testlibrary.BR;

import android.databinding.BaseObservable;

public class ObservableInLibrary extends BaseObservable {

    @Bindable
    private String mLibField1;

    @Bindable
    private String mLibField2;

    @Bindable
    private int mSharedField;

    public String getLibField1() {
        return mLibField1;
    }

    public void setLibField1(String libField1) {
        mLibField1 = libField1;
        notifyPropertyChanged(BR.libField1);
    }

    public String getLibField2() {
        return mLibField2;
    }

    public void setLibField2(String libField2) {
        mLibField2 = libField2;
        notifyPropertyChanged(BR.libField2);
    }

    public int getSharedField() {
        return mSharedField;
    }

    public void setSharedField(int sharedField) {
        mSharedField = sharedField;
        notifyPropertyChanged(BR.sharedField);
    }
}
