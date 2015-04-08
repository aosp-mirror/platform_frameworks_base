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
package android.databinding.testapp.vo;

import android.databinding.ObservableBoolean;
import android.databinding.ObservableByte;
import android.databinding.ObservableChar;
import android.databinding.ObservableDouble;
import android.databinding.ObservableField;
import android.databinding.ObservableFloat;
import android.databinding.ObservableInt;
import android.databinding.ObservableLong;
import android.databinding.ObservableShort;

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
