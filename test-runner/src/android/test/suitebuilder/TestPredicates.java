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
import android.test.suitebuilder.annotation.Smoke;
import android.test.suitebuilder.annotation.Suppress;

import com.android.internal.util.Predicate;

import java.lang.annotation.Annotation;

/**
 * {@hide} Not needed for 1.0 SDK.
 */
public class TestPredicates {

    static final Predicate<TestMethod> REJECT_INSTRUMENTATION =
            not(new AssignableFrom(InstrumentationTestCase.class));

    static final Predicate<TestMethod> SELECT_SMOKE = hasAnnotation(Smoke.class);

    static final Predicate<TestMethod> REJECT_SUPPRESSED = not(hasAnnotation(Suppress.class));

    /**
     * Return a predicate that checks to see if a {@link TestMethod} has an instance of the supplied
     * annotation class, either on the method or on the containing class.
     */
    public static Predicate<TestMethod> hasAnnotation(Class<? extends Annotation> annotationClass) {
        return new HasAnnotation(annotationClass);
    }

    private static class HasAnnotation implements Predicate<TestMethod> {

        private final Class<? extends Annotation> annotationClass;

        private HasAnnotation(Class<? extends Annotation> annotationClass) {
            this.annotationClass = annotationClass;
        }

        @Override
        public boolean apply(TestMethod testMethod) {
            return testMethod.getAnnotation(annotationClass) != null ||
                    testMethod.getEnclosingClass().getAnnotation(annotationClass) != null;
        }
    }

    /**
     * Returns a Predicate that evaluates to true iff the given Predicate
     * evaluates to false.
     */
    public static <T> Predicate<T> not(Predicate<? super T> predicate) {
        return new NotPredicate<T>(predicate);
    }

    private static class NotPredicate<T> implements Predicate<T> {
        private final Predicate<? super T> predicate;

        private NotPredicate(Predicate<? super T> predicate) {
            this.predicate = predicate;
        }

        public boolean apply(T t) {
            return !predicate.apply(t);
        }
    }
}
