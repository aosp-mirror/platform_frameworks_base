/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
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
import android.databinding.testapp.R;

public class PublicFinalWithObservableTestVo {
    public final int myField;
    public final MyVo myFinalVo = new MyVo();

    public PublicFinalWithObservableTestVo(int field) {
        myField = field;
    }

    public static class MyVo extends BaseObservable {
        @Bindable
        private int val = R.string.app_name;

        public int getVal() {
            return val;
        }

        public void setVal(int val) {
            this.val = val;
            notifyPropertyChanged(BR.val);
        }
    }
}
