/*
 * Copyright (C) 2016 The Android Open Source Project
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

package androidx.test.filters;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Use this annotation on test classes or test methods that should not be included in a test suite.
 * If the annotation appears on the class then no tests in that class will be included. If the
 * annotation appears only on a test method then only that method will be excluded.
 *
 * <p>Note: This class replaces the deprecated Android platform annotation <a
 * href="http://developer.android.com/reference/android/test/suitebuilder/annotation/Suppress.html">
 * android.test.suitebuilder.annotation.Suppress</a> and is the recommended way to suppress tests
 * written with the AndroidX Test Library.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Suppress {}
