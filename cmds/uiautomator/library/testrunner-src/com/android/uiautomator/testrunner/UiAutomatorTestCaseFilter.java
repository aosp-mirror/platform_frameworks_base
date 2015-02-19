/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.uiautomator.testrunner;

import com.android.uiautomator.testrunner.TestCaseCollector.TestCaseFilter;

import java.lang.reflect.Method;

/**
 * A {@link TestCaseFilter} that accepts testFoo methods and {@link UiAutomatorTestCase} classes
 *
 * @hide
 */
public class UiAutomatorTestCaseFilter implements TestCaseFilter {

    @Override
    public boolean accept(Method method) {
        return ((method.getParameterTypes().length == 0) &&
                (method.getName().startsWith("test")) &&
                (method.getReturnType().getSimpleName().equals("void")));
    }

    @Override
    public boolean accept(Class<?> clazz) {
        return UiAutomatorTestCase.class.isAssignableFrom(clazz);
    }

}
