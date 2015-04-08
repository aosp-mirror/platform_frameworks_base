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
import android.databinding.BaseObservable;
import android.databinding.Bindable;
import android.databinding.testapp.BR;

public class BasicObject extends BaseObservable {
    @Bindable
    private String mField1;
    @Bindable
    private String mField2;

    public String getField1() {
        return mField1;
    }

    public void setField1(String field1) {
        this.mField1 = field1;
        notifyPropertyChanged(BR.field1);
    }

    public String getField2() {
        return mField2;
    }

    public void setField2(String field2) {
        this.mField2 = field2;
        notifyPropertyChanged(BR.field1);
    }
}
