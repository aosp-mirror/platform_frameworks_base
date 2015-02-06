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
package com.android.databinding.testapp.vo;

import com.android.databinding.library.BaseObservable;
import com.android.databinding.library.ObservableBoolean;
import com.android.databinding.library.ObservableByte;
import com.android.databinding.library.ObservableChar;
import com.android.databinding.library.ObservableDouble;
import com.android.databinding.library.ObservableField;
import com.android.databinding.library.ObservableFloat;
import com.android.databinding.library.ObservableInt;
import com.android.databinding.library.ObservableLong;
import com.android.databinding.library.ObservableShort;

import android.binding.Bindable;

public class ObservableFieldBindingObject {
    public final ObservableBoolean bField = new ObservableBoolean();
    public final ObservableByte tField = new ObservableByte();
    public final ObservableShort sField = new ObservableShort();
    public final ObservableChar cField = new ObservableChar();
    public final ObservableInt iField = new ObservableInt();
    public final ObservableLong lField = new ObservableLong();
    public final ObservableFloat fField = new ObservableFloat();
    public final ObservableDouble dField = new ObservableDouble();
    public final ObservableField<String> oField = new ObservableField<>();

    public ObservableFieldBindingObject() {
        oField.set("Hello");
    }
}
