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

import com.android.databinding.testapp.generated.FindMethodTestBinder;
import com.android.databinding.testapp.vo.FindMethodBindingObject;

import android.test.UiThreadTest;
import android.widget.TextView;

public class FindMethodTest
        extends BindingAdapterTestBase<FindMethodTestBinder, FindMethodBindingObject> {

    public FindMethodTest() {
        super(FindMethodTestBinder.class, FindMethodBindingObject.class, R.layout.find_method_test);
    }

    public void testNoArg() throws Throwable {
        TextView textView = mBinder.getTextView6();
        assertEquals("no arg", textView.getText().toString());
    }

    public void testIntArg() throws Throwable {
        TextView textView = mBinder.getTextView0();
        assertEquals("1", textView.getText().toString());
    }

    public void testFloatArg() throws Throwable {
        TextView textView = mBinder.getTextView1();
        assertEquals("1.25", textView.getText().toString());
    }

    public void testStringArg() throws Throwable {
        TextView textView = mBinder.getTextView2();
        assertEquals("hello", textView.getText().toString());
    }

    public void testBoxedArg() throws Throwable {
        TextView textView = mBinder.getTextView3();
        assertEquals("1", textView.getText().toString());
    }

    public void testInheritedMethod() throws Throwable {
        TextView textView = mBinder.getTextView4();
        assertEquals("base", textView.getText().toString());
    }

    public void testInheritedMethodInt() throws Throwable {
        TextView textView = mBinder.getTextView5();
        assertEquals("base 2", textView.getText().toString());
    }

    public void testStaticMethod() throws Throwable {
        TextView textView = mBinder.getTextView7();
        assertEquals("world", textView.getText().toString());
    }

    public void testStaticField() throws Throwable {
        TextView textView = mBinder.getTextView8();
        assertEquals("hello world", textView.getText().toString());
    }

    public void testImportStaticMethod() throws Throwable {
        TextView textView = mBinder.getTextView9();
        assertEquals("world", textView.getText().toString());
    }

    public void testImportStaticField() throws Throwable {
        TextView textView = mBinder.getTextView10();
        assertEquals("hello world", textView.getText().toString());
    }

    public void testAliasStaticMethod() throws Throwable {
        TextView textView = mBinder.getTextView11();
        assertEquals("world", textView.getText().toString());
    }

    public void testAliasStaticField() throws Throwable {
        TextView textView = mBinder.getTextView12();
        assertEquals("hello world", textView.getText().toString());
    }

    @UiThreadTest
    public void testImports() throws Throwable {
        mBinder.setObj2(new FindMethodBindingObject.Bar<String>());
        mBinder.rebindDirty();
        TextView textView = mBinder.getTextView15();
        assertEquals("hello", textView.getText().toString());
    }
}
