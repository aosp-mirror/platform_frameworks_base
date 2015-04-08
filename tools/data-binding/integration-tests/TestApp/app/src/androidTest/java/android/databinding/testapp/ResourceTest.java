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

import android.databinding.testapp.databinding.ResourceTestBinding;

import android.test.UiThreadTest;
import android.widget.TextView;

public class ResourceTest extends BaseDataBinderTest<ResourceTestBinding> {

    public ResourceTest() {
        super(ResourceTestBinding.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mBinder.setCount(0);
        mBinder.setTitle("Mrs.");
        mBinder.setLastName("Doubtfire");
        mBinder.setBase(2);
        mBinder.setPbase(3);
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBinder.executePendingBindings();
                }
            });
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    @UiThreadTest
    public void testStringFormat() throws Throwable {
        TextView view = mBinder.textView0;
        assertEquals("Mrs. Doubtfire", view.getText().toString());

        mBinder.setTitle("Mr.");
        mBinder.executePendingBindings();
        assertEquals("Mr. Doubtfire", view.getText().toString());
    }

    @UiThreadTest
    public void testQuantityString() throws Throwable {
        TextView view = mBinder.textView1;
        assertEquals("oranges", view.getText().toString());

        mBinder.setCount(1);
        mBinder.executePendingBindings();
        assertEquals("orange", view.getText().toString());
    }

    @UiThreadTest
    public void testFractionNoParameters() throws Throwable {
        TextView view = mBinder.fractionNoParameters;
        assertEquals("1.5", view.getText().toString());
    }

    @UiThreadTest
    public void testFractionOneParameter() throws Throwable {
        TextView view = mBinder.fractionOneParameter;
        assertEquals("3.0", view.getText().toString());
    }

    @UiThreadTest
    public void testFractionTwoParameters() throws Throwable {
        TextView view = mBinder.fractionTwoParameters;
        assertEquals("9.0", view.getText().toString());
    }
}
