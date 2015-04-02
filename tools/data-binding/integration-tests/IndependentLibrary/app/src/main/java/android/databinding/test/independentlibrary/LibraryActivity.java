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

package android.databinding.test.independentlibrary;

import android.app.Activity;
import android.os.Bundle;

import android.databinding.test.independentlibrary.vo.MyBindableObject;
import android.databinding.test.independentlibrary.databinding.LibraryLayoutBinding;
public class LibraryActivity extends Activity {
    public static final String FIELD_VALUE = "BAR";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LibraryLayoutBinding binding = LibraryLayoutBinding.inflate(this);
        setContentView(binding.getRoot());
        MyBindableObject object = new MyBindableObject();
        object.setField(FIELD_VALUE);
        binding.setFoo(object);
        binding.executePendingBindings();
    }
}
