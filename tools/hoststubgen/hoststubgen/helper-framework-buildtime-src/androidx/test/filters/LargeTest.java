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
 * Annotation to assign a large test size qualifier to a test. This annotation can be used at a
 * method or class level.
 *
 * <p>Test size qualifiers are a great way to structure test code and are used to assign a test to a
 * test suite of similar run time.
 *
 * <p>Execution time: &gt;1000ms
 *
 * <p>Large tests should be focused on testing integration of all application components. These
 * tests fully participate in the system and may make use of all resources such as databases, file
 * systems and network. As a rule of thumb most functional UI tests are large tests.
 *
 * <p>Note: This class replaces the deprecated Android platform size qualifier <a
 * href="{@docRoot}reference/android/test/suitebuilder/annotation/LargeTest.html"><code>
 * android.test.suitebuilder.annotation.LargeTest</code></a> and is the recommended way to annotate
 * tests written with the AndroidX Test Library.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LargeTest {}
