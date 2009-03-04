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

package com.android.internal.util;

import java.util.Arrays;

/**
 * Predicates contains static methods for creating the standard set of
 * {@code Predicate} objects.
 */
public class Predicates {

    private Predicates() {
    }

    /**
     * Returns a Predicate that evaluates to true iff each of its components
     * evaluates to true.  The components are evaluated in order, and evaluation
     * will be "short-circuited" as soon as the answer is determined.
     */
    public static <T> Predicate<T> and(Predicate<? super T>... components) {
        return and(Arrays.asList(components));
    }

    /**
     * Returns a Predicate that evaluates to true iff each of its components
     * evaluates to true.  The components are evaluated in order, and evaluation
     * will be "short-circuited" as soon as the answer is determined.  Does not
     * defensively copy the iterable passed in, so future changes to it will alter
     * the behavior of this Predicate. If components is empty, the returned
     * Predicate will always evaluate to true.
     */
    public static <T> Predicate<T> and(Iterable<? extends Predicate<? super T>> components) {
        return new AndPredicate(components);
    }

    /**
     * Returns a Predicate that evaluates to true iff any one of its components
     * evaluates to true.  The components are evaluated in order, and evaluation
     * will be "short-circuited" as soon as the answer is determined.
     */
    public static <T> Predicate<T> or(Predicate<? super T>... components) {
        return or(Arrays.asList(components));
    }

    /**
     * Returns a Predicate that evaluates to true iff any one of its components
     * evaluates to true.  The components are evaluated in order, and evaluation
     * will be "short-circuited" as soon as the answer is determined.  Does not
     * defensively copy the iterable passed in, so future changes to it will alter
     * the behavior of this Predicate. If components is empty, the returned
     * Predicate will always evaluate to false.
     */
    public static <T> Predicate<T> or(Iterable<? extends Predicate<? super T>> components) {
        return new OrPredicate(components);
    }

    /**
     * Returns a Predicate that evaluates to true iff the given Predicate
     * evaluates to false.
     */
    public static <T> Predicate<T> not(Predicate<? super T> predicate) {
        return new NotPredicate<T>(predicate);
    }

    private static class AndPredicate<T> implements Predicate<T> {
        private final Iterable<? extends Predicate<? super T>> components;

        private AndPredicate(Iterable<? extends Predicate<? super T>> components) {
            this.components = components;
        }

        public boolean apply(T t) {
            for (Predicate<? super T> predicate : components) {
                if (!predicate.apply(t)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class OrPredicate<T> implements Predicate<T> {
        private final Iterable<? extends Predicate<? super T>> components;

        private OrPredicate(Iterable<? extends Predicate<? super T>> components) {
            this.components = components;
        }

        public boolean apply(T t) {
            for (Predicate<? super T> predicate : components) {
                if (predicate.apply(t)) {
                    return true;
                }
            }
            return false;
        }
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
