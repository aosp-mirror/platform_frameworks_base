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
package android.databinding.testapp;

import android.databinding.testapp.databinding.InnerCannotReadDependencyBinding;
import android.databinding.testapp.vo.BasicObject;
import android.os.Debug;
import android.test.UiThreadTest;

import org.junit.Test;

public class InnerCannotReadDependencyTest extends
        BaseDataBinderTest<InnerCannotReadDependencyBinding> {

    public InnerCannotReadDependencyTest() {
        super(InnerCannotReadDependencyBinding.class);
    }

    @UiThreadTest
    public void testBinding() {
        BasicObject object = new BasicObject();
        object.setField1("a");
        mBinder.setObj(object);
        mBinder.executePendingBindings();
        assertEquals("a ", mBinder.textView.getText().toString());
        object.setField1(null);
        mBinder.executePendingBindings();
        assertEquals("null ", mBinder.textView.getText().toString());
        object.setField2("b");
        mBinder.executePendingBindings();
        assertEquals("null b", mBinder.textView.getText().toString());
        object.setField1("c");
        mBinder.executePendingBindings();
        assertEquals("c b", mBinder.textView.getText().toString());
    }
}
