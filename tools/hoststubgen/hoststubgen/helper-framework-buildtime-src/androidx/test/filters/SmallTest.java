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
 * Annotation to assign a small test size qualifier to a test. This annotation can be used at a
 * method or class level.
 *
 * <p>Test size qualifiers are a great way to structure test code and are used to assign a test to a
 * test suite of similar run time.
 *
 * <p>Execution time: &lt;200ms
 *
 * <p>Small tests should be run very frequently. Focused on units of code to verify specific logical
 * conditions. These tests should runs in an isolated environment and use mock objects for external
 * dependencies. Resource access (such as file system, network, or databases) are not permitted.
 * Tests that interact with hardware, make binder calls, or that facilitate android instrumentation
 * should not use this annotation.
 *
 * <p>Note: This class replaces the deprecated Android platform size qualifier <a
 * href="http://developer.android.com/reference/android/test/suitebuilder/annotation/SmallTest.html">
 * android.test.suitebuilder.annotation.SmallTest</a> and is the recommended way to annotate tests
 * written with the AndroidX Test Library.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SmallTest {}
