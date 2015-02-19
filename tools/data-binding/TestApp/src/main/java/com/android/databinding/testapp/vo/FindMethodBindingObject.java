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

public class FindMethodBindingObject extends FindMethodBindingObjectBase {
    public String method() { return "no arg"; }

    public String method(int i) { return String.valueOf(i); }

    public String method(float f) { return String.valueOf(f); }

    public String method(String value) { return value; }

    public static String staticMethod() { return "world"; }

    public static Foo foo = new Foo();


    public static class Foo {
        public final String bar = "hello world";
    }
}
