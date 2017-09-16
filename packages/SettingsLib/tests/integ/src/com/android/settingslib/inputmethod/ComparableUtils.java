/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.inputmethod;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/**
 * Utility class to assert {@link Comparable} objects.
 */
final class ComparableUtils {

    private ComparableUtils() {
    }

    /**
     * Checks whether specified items have ascending ordering.
     *
     * @param items     objects to be checked Comparable contracts.
     * @param compareTo function to compare two objects as {@link Comparable#compareTo(Object)}.
     * @param name      function to extract name of an object.
     * @param <T>       type that implements {@link Comparable}.
     */
    static <T> void assertAscendingOrdering(final List<T> items,
            final ToIntBiFunction<T, T> compareTo, final Function<T, String> name) {
        for (int i = 1; i < items.size(); i++) {
            final T x = items.get(i - 1);
            final T y = items.get(i);
            assertTrue(name.apply(x) + " is less than " + name.apply(y),
                    compareTo.applyAsInt(x, y) < 0);
        }
    }

    /**
     * Checks whether specified items have the same ordering.
     *
     * @param items     objects to be checked equality.
     * @param compareTo function to compare two objects as {@link Comparable#compareTo(Object)}.
     * @param name      function to extract name of an object.
     * @param <T>       type that implements {@link Comparable}.
     */
    static <T> void assertSameOrdering(final Collection<T> items,
            final ToIntBiFunction<T, T> compareTo, final Function<T, String> name) {
        for (final T x : items) {
            for (final T y : items) {
                assertTrue(name.apply(x) + " is equal to " + name.apply(y),
                        compareTo.applyAsInt(x, y) == 0);
            }
        }
    }

    /**
     * Checks whether a {@link Comparable} type complies with Comparable contracts.
     * <ul>
     * <li>Ensure sgn(x.compareTo(y)) == -sgn(y.compareTo(x)) for all x and y.</li>
     * <li>Ensure that the relation is transitive:
     *     (x.compareTo(y)>0 && y.compareTo(z)>0) implies x.compareTo(z)>0.</li>
     * <li>Ensure that x.compareTo(y)==0 implies that sgn(x.compareTo(z)) == sgn(y.compareTo(z)),
     *     for all z.</li>
     * </ul>
     *
     * @param items     objects to be checked Comparable contracts.
     * @param compareTo function to compare two objects as {@link Comparable#compareTo(Object)}.
     * @param name      function to extract name of an object.
     * @param <T>       type that implements {@link Comparable}.
     */
    static <T> void assertComparableContracts(final Collection<T> items,
            final ToIntBiFunction<T, T> compareTo, final Function<T, String> name) {
        for (final T x : items) {
            final String nameX = name.apply(x);
            assertTrue("Reflective: " + nameX + " is equal to itself",
                    compareTo.applyAsInt(x, x) == 0);
            for (final T y : items) {
                final String nameY = name.apply(y);
                assertEquals("Asymmetric: " + nameX + " and " + nameY,
                        Integer.signum(compareTo.applyAsInt(x, y)),
                        -Integer.signum(compareTo.applyAsInt(y, x)));
                for (final T z : items) {
                    final String nameZ = name.apply(z);
                    if (compareTo.applyAsInt(x, y) > 0 && compareTo.applyAsInt(y, z) > 0) {
                        assertTrue("Transitive: " + nameX + " is greater than " + nameY
                                        + " and " + nameY + " is greater than " + nameZ
                                        + " then " + nameX + " is greater than " + nameZ,
                                compareTo.applyAsInt(x, z) > 0);
                    }
                    if (compareTo.applyAsInt(x, y) == 0) {
                        assertEquals("Transitive: " + nameX + " and " + nameY + " is same "
                                        + " then both return the same result for " + nameZ,
                                Integer.signum(compareTo.applyAsInt(x, z)),
                                Integer.signum(compareTo.applyAsInt(y, z)));
                    }
                }
            }
        }
    }
}
