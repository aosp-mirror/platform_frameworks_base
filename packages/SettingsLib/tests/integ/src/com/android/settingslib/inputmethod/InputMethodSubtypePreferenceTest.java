package com.android.settingslib.inputmethod;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodSubtypePreferenceTest {

    private static final List<InputMethodSubtypePreference> ITEMS_IN_ASCENDING = Arrays.asList(
            // Subtypes that has the same locale of the system's.
            createPreference("", "en_US", Locale.US),
            createPreference("E", "en_US", Locale.US),
            createPreference("Z", "en_US", Locale.US),
            // Subtypes that has the same language of the system's.
            createPreference("", "en", Locale.US),
            createPreference("E", "en", Locale.US),
            createPreference("Z", "en", Locale.US),
            // Subtypes that has different language than the system's.
            createPreference("", "ja", Locale.US),
            createPreference("A", "hi_IN", Locale.US),
            createPreference("B", "", Locale.US),
            createPreference("E", "ja", Locale.US),
            createPreference("Z", "ja", Locale.US)
    );
    private static final List<InputMethodSubtypePreference> SAME_ITEMS = Arrays.asList(
            // Subtypes that has different language than the system's.
            createPreference("A", "ja_JP", Locale.US),
            createPreference("A", "hi_IN", Locale.US),
            // Subtypes that has an empty subtype locale string.
            createPreference("A", "", Locale.US)
    );
    private static final Collator COLLATOR = Collator.getInstance(Locale.US);

    @Test
    public void testComparableOrdering() throws Exception {
        onAllAdjacentItems(ITEMS_IN_ASCENDING,
                (x, y) -> assertTrue(
                        x.getKey() + " is less than " + y.getKey(),
                        x.compareTo(y, COLLATOR) < 0)
        );
    }

    @Test
    public void testComparableEquality() {
        onAllAdjacentItems(SAME_ITEMS,
                (x, y) -> assertTrue(
                        x.getKey() + " is equal to " + y.getKey(),
                        x.compareTo(y, COLLATOR) == 0)
        );
    }

    @Test
    public void testComparableContracts() {
        final Collection<InputMethodSubtypePreference> items = new ArrayList<>();
        items.addAll(ITEMS_IN_ASCENDING);
        items.addAll(SAME_ITEMS);
        items.add(createPreference("", "", Locale.US));
        items.add(createPreference("A", "en", Locale.US));
        items.add(createPreference("A", "en_US", Locale.US));
        items.add(createPreference("E", "hi_IN", Locale.US));
        items.add(createPreference("E", "en", Locale.US));
        items.add(createPreference("Z", "en_US", Locale.US));

        assertComparableContracts(
                items,
                (x, y) -> x.compareTo(y, COLLATOR),
                InputMethodSubtypePreference::getKey);
    }

    private static InputMethodSubtypePreference createPreference(
            final String subtypeName,
            final String subtypeLocaleString,
            final Locale systemLocale) {
        return new InputMethodSubtypePreference(
                InstrumentationRegistry.getTargetContext(),
                subtypeName + "-" + subtypeLocaleString + "-" + systemLocale,
                subtypeName,
                subtypeLocaleString,
                systemLocale);
    }

    private static <T> void onAllAdjacentItems(final List<T> items, final BiConsumer<T, T> check) {
        for (int i = 0; i < items.size() - 1; i++) {
            check.accept(items.get(i), items.get(i + 1));
        }
    }

    @FunctionalInterface
    interface CompareTo<T> {
        int apply(T t, T u);
    }

    private static <T> void assertComparableContracts(final Collection<T> items,
            final CompareTo<T> compareTo, final Function<T, String> name) {
        for (final T x : items) {
            final String nameX = name.apply(x);
            assertTrue("Reflective: " + nameX + " is equal to itself",
                    compareTo.apply(x, x) == 0);
            for (final T y : items) {
                final String nameY = name.apply(y);
                assertEquals("Asymmetric: " + nameX + " and " + nameY,
                        Integer.signum(compareTo.apply(x, y)),
                        -Integer.signum(compareTo.apply(y, x)));
                for (final T z : items) {
                    final String nameZ = name.apply(z);
                    if (compareTo.apply(x, y) > 0 && compareTo.apply(y, z) > 0) {
                        assertTrue("Transitive: " + nameX + " is greater than " + nameY
                                        + " and " + nameY + " is greater than " + nameZ
                                        + " then " + nameX + " is greater than " + nameZ,
                                compareTo.apply(x, z) > 0);
                    }
                    if (compareTo.apply(x, y) == 0) {
                        assertEquals("Transitive: " + nameX + " and " + nameY + " is same "
                                        + " then both return the same result for " + nameZ,
                                Integer.signum(compareTo.apply(x, z)),
                                Integer.signum(compareTo.apply(y, z)));
                    }
                }
            }
        }
    }
}
