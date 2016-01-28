/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This annotation can be used on an {@link junit.framework.TestCase}'s test methods. When the
 * annotation is present, the test method is timed and the results written through instrumentation
 * output. It can also be used on the class itself, which is equivalent to tagging all test methods
 * with this annotation.
 * <p/>
 * Setting {@link #includeDetailedStats()} to true causes additional metrics such as memory usage
 * and binder transactions to be gathered and written through instrumentation output.
 *
 * {@hide} Pending approval for public API.
 */
@Deprecated
@Retention(RetentionPolicy.RUNTIME)
public @interface TimedTest {
    boolean includeDetailedStats() default false;
}
