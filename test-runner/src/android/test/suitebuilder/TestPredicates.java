/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test.suitebuilder;

import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.HasAnnotation;
import android.test.suitebuilder.annotation.Suppress;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Smoke;
import com.android.internal.util.Predicate;
import com.android.internal.util.Predicates;

/**
 * {@hide} Not needed for 1.0 SDK.
 */
public class TestPredicates {

    public static final Predicate<TestMethod> SELECT_INSTRUMENTATION =
            new AssignableFrom(InstrumentationTestCase.class);
    public static final Predicate<TestMethod> REJECT_INSTRUMENTATION =
            Predicates.not(SELECT_INSTRUMENTATION);

    public static final Predicate<TestMethod> SELECT_SMOKE = new HasAnnotation(Smoke.class);
    public static final Predicate<TestMethod> SELECT_SMALL = new HasAnnotation(SmallTest.class);
    public static final Predicate<TestMethod> SELECT_MEDIUM = new HasAnnotation(MediumTest.class);
    public static final Predicate<TestMethod> SELECT_LARGE = new HasAnnotation(LargeTest.class);
    public static final Predicate<TestMethod> REJECT_SUPPRESSED =
            Predicates.not(new HasAnnotation(Suppress.class));

}
