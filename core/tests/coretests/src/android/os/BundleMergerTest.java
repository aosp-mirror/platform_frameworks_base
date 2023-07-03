/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os;

import static android.os.BundleMerger.STRATEGY_ARRAY_APPEND;
import static android.os.BundleMerger.STRATEGY_ARRAY_LIST_APPEND;
import static android.os.BundleMerger.STRATEGY_BOOLEAN_AND;
import static android.os.BundleMerger.STRATEGY_BOOLEAN_OR;
import static android.os.BundleMerger.STRATEGY_COMPARABLE_MAX;
import static android.os.BundleMerger.STRATEGY_COMPARABLE_MIN;
import static android.os.BundleMerger.STRATEGY_FIRST;
import static android.os.BundleMerger.STRATEGY_LAST;
import static android.os.BundleMerger.STRATEGY_NUMBER_ADD;
import static android.os.BundleMerger.STRATEGY_NUMBER_INCREMENT_FIRST;
import static android.os.BundleMerger.STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD;
import static android.os.BundleMerger.STRATEGY_REJECT;
import static android.os.BundleMerger.merge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Intent;
import android.net.Uri;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(JUnit4.class)
public class BundleMergerTest {
    /**
     * Strategies are only applied when there is an actual conflict; in the
     * absence of conflict we pick whichever value is defined.
     */
    @Test
    public void testNoConflict() throws Exception {
        for (int strategy = Byte.MIN_VALUE; strategy < Byte.MAX_VALUE; strategy++) {
            assertEquals(null, merge(strategy, null, null));
            assertEquals(10, merge(strategy, 10, null));
            assertEquals(20, merge(strategy, null, 20));
        }
    }

    /**
     * Strategies are only applied to identical data types; if there are mixed
     * types we always reject the two conflicting values.
     */
    @Test
    public void testMixedTypes() throws Exception {
        for (int strategy = Byte.MIN_VALUE; strategy < Byte.MAX_VALUE; strategy++) {
            final int finalStrategy = strategy;
            assertThrows(Exception.class, () -> {
                merge(finalStrategy, 10, "foo");
            });
            assertThrows(Exception.class, () -> {
                merge(finalStrategy, List.of("foo"), "bar");
            });
            assertThrows(Exception.class, () -> {
                merge(finalStrategy, new String[] { "foo" }, "bar");
            });
            assertThrows(Exception.class, () -> {
                merge(finalStrategy, Integer.valueOf(10), Long.valueOf(10));
            });
        }
    }

    @Test
    public void testStrategyReject() throws Exception {
        assertEquals(null, merge(STRATEGY_REJECT, 10, 20));

        // Identical values aren't technically a conflict, so they're passed
        // through without being rejected
        assertEquals(10, merge(STRATEGY_REJECT, 10, 10));
        assertArrayEquals(new int[] {10},
                (int[]) merge(STRATEGY_REJECT, new int[] {10}, new int[] {10}));
    }

    @Test
    public void testStrategyFirst() throws Exception {
        assertEquals(10, merge(STRATEGY_FIRST, 10, 20));
    }

    @Test
    public void testStrategyLast() throws Exception {
        assertEquals(20, merge(STRATEGY_LAST, 10, 20));
    }

    @Test
    public void testStrategyComparableMin() throws Exception {
        assertEquals(10, merge(STRATEGY_COMPARABLE_MIN, 10, 20));
        assertEquals(10, merge(STRATEGY_COMPARABLE_MIN, 20, 10));
        assertEquals("a", merge(STRATEGY_COMPARABLE_MIN, "a", "z"));
        assertEquals("a", merge(STRATEGY_COMPARABLE_MIN, "z", "a"));

        assertThrows(Exception.class, () -> {
            merge(STRATEGY_COMPARABLE_MIN, new Binder(), new Binder());
        });
    }

    @Test
    public void testStrategyComparableMax() throws Exception {
        assertEquals(20, merge(STRATEGY_COMPARABLE_MAX, 10, 20));
        assertEquals(20, merge(STRATEGY_COMPARABLE_MAX, 20, 10));
        assertEquals("z", merge(STRATEGY_COMPARABLE_MAX, "a", "z"));
        assertEquals("z", merge(STRATEGY_COMPARABLE_MAX, "z", "a"));

        assertThrows(Exception.class, () -> {
            merge(STRATEGY_COMPARABLE_MAX, new Binder(), new Binder());
        });
    }

    @Test
    public void testStrategyNumberAdd() throws Exception {
        assertEquals(30, merge(STRATEGY_NUMBER_ADD, 10, 20));
        assertEquals(30, merge(STRATEGY_NUMBER_ADD, 20, 10));
        assertEquals(30L, merge(STRATEGY_NUMBER_ADD, 10L, 20L));
        assertEquals(30L, merge(STRATEGY_NUMBER_ADD, 20L, 10L));

        assertThrows(Exception.class, () -> {
            merge(STRATEGY_NUMBER_ADD, new Binder(), new Binder());
        });
    }

    @Test
    public void testStrategyNumberIncrementFirst() throws Exception {
        assertEquals(11, merge(STRATEGY_NUMBER_INCREMENT_FIRST, 10, 20));
        assertEquals(21, merge(STRATEGY_NUMBER_INCREMENT_FIRST, 20, 10));
        assertEquals(11L, merge(STRATEGY_NUMBER_INCREMENT_FIRST, 10L, 20L));
        assertEquals(21L, merge(STRATEGY_NUMBER_INCREMENT_FIRST, 20L, 10L));
    }

    @Test
    public void testStrategyNumberIncrementFirstAndAdd() throws Exception {
        assertEquals(31, merge(STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD, 10, 20));
        assertEquals(31, merge(STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD, 20, 10));
        assertEquals(31L, merge(STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD, 10L, 20L));
        assertEquals(31L, merge(STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD, 20L, 10L));
    }

    @Test
    public void testStrategyBooleanAnd() throws Exception {
        assertEquals(false, merge(STRATEGY_BOOLEAN_AND, false, false));
        assertEquals(false, merge(STRATEGY_BOOLEAN_AND, true, false));
        assertEquals(false, merge(STRATEGY_BOOLEAN_AND, false, true));
        assertEquals(true, merge(STRATEGY_BOOLEAN_AND, true, true));

        assertThrows(Exception.class, () -> {
            merge(STRATEGY_BOOLEAN_AND, "True!", "False?");
        });
    }

    @Test
    public void testStrategyBooleanOr() throws Exception {
        assertEquals(false, merge(STRATEGY_BOOLEAN_OR, false, false));
        assertEquals(true, merge(STRATEGY_BOOLEAN_OR, true, false));
        assertEquals(true, merge(STRATEGY_BOOLEAN_OR, false, true));
        assertEquals(true, merge(STRATEGY_BOOLEAN_OR, true, true));

        assertThrows(Exception.class, () -> {
            merge(STRATEGY_BOOLEAN_OR, "True!", "False?");
        });
    }

    @Test
    public void testStrategyArrayAppend() throws Exception {
        assertArrayEquals(new int[] {},
                (int[]) merge(STRATEGY_ARRAY_APPEND, new int[] {}, new int[] {}));
        assertArrayEquals(new int[] {10},
                (int[]) merge(STRATEGY_ARRAY_APPEND, new int[] {10}, new int[] {}));
        assertArrayEquals(new int[] {20},
                (int[]) merge(STRATEGY_ARRAY_APPEND, new int[] {}, new int[] {20}));
        assertArrayEquals(new int[] {10, 20},
                (int[]) merge(STRATEGY_ARRAY_APPEND, new int[] {10}, new int[] {20}));
        assertArrayEquals(new int[] {10, 30, 20, 40},
                (int[]) merge(STRATEGY_ARRAY_APPEND, new int[] {10, 30}, new int[] {20, 40}));
        assertArrayEquals(new String[] {"a", "b"},
                (String[]) merge(STRATEGY_ARRAY_APPEND, new String[] {"a"}, new String[] {"b"}));

        assertThrows(Exception.class, () -> {
            merge(STRATEGY_ARRAY_APPEND, 10, 20);
        });
    }

    @Test
    public void testStrategyArrayListAppend() throws Exception {
        assertEquals(arrayListOf(),
                merge(STRATEGY_ARRAY_LIST_APPEND, arrayListOf(), arrayListOf()));
        assertEquals(arrayListOf(10),
                merge(STRATEGY_ARRAY_LIST_APPEND, arrayListOf(10), arrayListOf()));
        assertEquals(arrayListOf(20),
                merge(STRATEGY_ARRAY_LIST_APPEND, arrayListOf(), arrayListOf(20)));
        assertEquals(arrayListOf(10, 20),
                merge(STRATEGY_ARRAY_LIST_APPEND, arrayListOf(10), arrayListOf(20)));
        assertEquals(arrayListOf(10, 30, 20, 40),
                merge(STRATEGY_ARRAY_LIST_APPEND, arrayListOf(10, 30), arrayListOf(20, 40)));
        assertEquals(arrayListOf("a", "b"),
                merge(STRATEGY_ARRAY_LIST_APPEND, arrayListOf("a"), arrayListOf("b")));

        assertThrows(Exception.class, () -> {
            merge(STRATEGY_ARRAY_LIST_APPEND, 10, 20);
        });
    }

    @Test
    public void testSetDefaultMergeStrategy() throws Exception {
        final BundleMerger merger = new BundleMerger();
        merger.setDefaultMergeStrategy(STRATEGY_FIRST);
        merger.setMergeStrategy(Intent.EXTRA_INDEX, STRATEGY_COMPARABLE_MAX);

        Bundle a = new Bundle();
        a.putString(Intent.EXTRA_SUBJECT, "SubjectA");
        a.putInt(Intent.EXTRA_INDEX, 10);

        Bundle b = new Bundle();
        b.putString(Intent.EXTRA_SUBJECT, "SubjectB");
        b.putInt(Intent.EXTRA_INDEX, 20);

        Bundle ab = merger.merge(a, b);
        assertEquals("SubjectA", ab.getString(Intent.EXTRA_SUBJECT));
        assertEquals(20, ab.getInt(Intent.EXTRA_INDEX));

        Bundle ba = merger.merge(b, a);
        assertEquals("SubjectB", ba.getString(Intent.EXTRA_SUBJECT));
        assertEquals(20, ba.getInt(Intent.EXTRA_INDEX));
    }

    @Test
    public void testMerge_Simple() throws Exception {
        final BundleMerger merger = new BundleMerger();
        final Bundle probe = new Bundle();
        probe.putInt(Intent.EXTRA_INDEX, 42);

        assertEquals(null, merger.merge(null, null));
        assertEquals(probe.keySet(), merger.merge(probe, null).keySet());
        assertEquals(probe.keySet(), merger.merge(null, probe).keySet());
        assertEquals(probe.keySet(), merger.merge(probe, probe).keySet());
    }

    /**
     * Verify that we can merge parcelables present in the base classpath, since
     * everyone on the device will be able to unpack them.
     */
    @Test
    public void testMerge_Parcelable_BCP() throws Exception {
        final BundleMerger merger = new BundleMerger();
        merger.setMergeStrategy(Intent.EXTRA_STREAM, STRATEGY_COMPARABLE_MIN);

        Bundle a = new Bundle();
        a.putParcelable(Intent.EXTRA_STREAM, Uri.parse("http://example.com"));
        a = parcelAndUnparcel(a);

        Bundle b = new Bundle();
        b.putParcelable(Intent.EXTRA_STREAM, Uri.parse("http://example.net"));
        b = parcelAndUnparcel(b);

        assertEquals(Uri.parse("http://example.com"),
                merger.merge(a, b).getParcelable(Intent.EXTRA_STREAM, Uri.class));
        assertEquals(Uri.parse("http://example.com"),
                merger.merge(b, a).getParcelable(Intent.EXTRA_STREAM, Uri.class));
    }

    /**
     * Verify that we tiptoe around custom parcelables while still merging other
     * known data types. Custom parcelables aren't in the base classpath, so not
     * everyone on the device will be able to unpack them.
     */
    @Test
    public void testMerge_Parcelable_Custom() throws Exception {
        final BundleMerger merger = new BundleMerger();
        merger.setMergeStrategy(Intent.EXTRA_INDEX, STRATEGY_NUMBER_ADD);

        Bundle a = new Bundle();
        a.putInt(Intent.EXTRA_INDEX, 10);
        a.putString(Intent.EXTRA_CC, "foo@bar.com");
        a.putParcelable(Intent.EXTRA_SUBJECT, new ExplodingParcelable());
        a = parcelAndUnparcel(a);

        Bundle b = new Bundle();
        b.putInt(Intent.EXTRA_INDEX, 20);
        a.putString(Intent.EXTRA_BCC, "foo@baz.com");
        b.putParcelable(Intent.EXTRA_STREAM, new ExplodingParcelable());
        b = parcelAndUnparcel(b);

        Bundle ab = merger.merge(a, b);
        assertEquals(Set.of(Intent.EXTRA_INDEX, Intent.EXTRA_CC, Intent.EXTRA_BCC,
                Intent.EXTRA_SUBJECT, Intent.EXTRA_STREAM), ab.keySet());
        assertEquals(30, ab.getInt(Intent.EXTRA_INDEX));
        assertEquals("foo@bar.com", ab.getString(Intent.EXTRA_CC));
        assertEquals("foo@baz.com", ab.getString(Intent.EXTRA_BCC));

        // And finally, make sure that if we try unpacking one of our custom
        // values that we actually explode
        assertThrows(BadParcelableException.class, () -> {
            ab.getParcelable(Intent.EXTRA_SUBJECT, ExplodingParcelable.class);
        });
        assertThrows(BadParcelableException.class, () -> {
            ab.getParcelable(Intent.EXTRA_STREAM, ExplodingParcelable.class);
        });
    }

    @Test
    public void testMerge_PackageChanged() throws Exception {
        final BundleMerger merger = new BundleMerger();
        merger.setMergeStrategy(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, STRATEGY_ARRAY_APPEND);

        final Bundle first = new Bundle();
        first.putInt(Intent.EXTRA_UID, 10001);
        first.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, new String[] {
                "com.example.Foo",
        });

        final Bundle second = new Bundle();
        second.putInt(Intent.EXTRA_UID, 10001);
        second.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, new String[] {
                "com.example.Bar",
                "com.example.Baz",
        });

        final Bundle res = merger.merge(first, second);
        assertEquals(10001, res.getInt(Intent.EXTRA_UID));
        assertArrayEquals(new String[] {
                "com.example.Foo", "com.example.Bar", "com.example.Baz",
        }, res.getStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST));
    }

    /**
     * Each event in isolation reports "zero events dropped", but if we need to
     * merge them together, then we start incrementing.
     */
    @Test
    public void testMerge_DropBox() throws Exception {
        final BundleMerger merger = new BundleMerger();
        merger.setMergeStrategy(DropBoxManager.EXTRA_TIME,
                STRATEGY_COMPARABLE_MAX);
        merger.setMergeStrategy(DropBoxManager.EXTRA_DROPPED_COUNT,
                STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD);

        final long now = System.currentTimeMillis();
        final Bundle a = new Bundle();
        a.putString(DropBoxManager.EXTRA_TAG, "system_server_strictmode");
        a.putLong(DropBoxManager.EXTRA_TIME, now);
        a.putInt(DropBoxManager.EXTRA_DROPPED_COUNT, 0);

        final Bundle b = new Bundle();
        b.putString(DropBoxManager.EXTRA_TAG, "system_server_strictmode");
        b.putLong(DropBoxManager.EXTRA_TIME, now + 1000);
        b.putInt(DropBoxManager.EXTRA_DROPPED_COUNT, 0);

        final Bundle c = new Bundle();
        c.putString(DropBoxManager.EXTRA_TAG, "system_server_strictmode");
        c.putLong(DropBoxManager.EXTRA_TIME, now + 2000);
        c.putInt(DropBoxManager.EXTRA_DROPPED_COUNT, 0);

        final Bundle d = new Bundle();
        d.putString(DropBoxManager.EXTRA_TAG, "system_server_strictmode");
        d.putLong(DropBoxManager.EXTRA_TIME, now + 3000);
        d.putInt(DropBoxManager.EXTRA_DROPPED_COUNT, 5);

        final Bundle ab = merger.merge(a, b);
        assertEquals("system_server_strictmode", ab.getString(DropBoxManager.EXTRA_TAG));
        assertEquals(now + 1000, ab.getLong(DropBoxManager.EXTRA_TIME));
        assertEquals(1, ab.getInt(DropBoxManager.EXTRA_DROPPED_COUNT));

        final Bundle abc = merger.merge(ab, c);
        assertEquals("system_server_strictmode", abc.getString(DropBoxManager.EXTRA_TAG));
        assertEquals(now + 2000, abc.getLong(DropBoxManager.EXTRA_TIME));
        assertEquals(2, abc.getInt(DropBoxManager.EXTRA_DROPPED_COUNT));

        final Bundle abcd = merger.merge(abc, d);
        assertEquals("system_server_strictmode", abcd.getString(DropBoxManager.EXTRA_TAG));
        assertEquals(now + 3000, abcd.getLong(DropBoxManager.EXTRA_TIME));
        assertEquals(8, abcd.getInt(DropBoxManager.EXTRA_DROPPED_COUNT));
    }

    private static ArrayList<Object> arrayListOf(Object... values) {
        final ArrayList<Object> res = new ArrayList<>(values.length);
        for (Object value : values) {
            res.add(value);
        }
        return res;
    }

    private static Bundle parcelAndUnparcel(Bundle input) {
        final Parcel parcel = Parcel.obtain();
        try {
            input.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Bundle.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * Object that only offers to parcel itself; if something tries unparceling
     * it, it will "explode" by throwing an exception.
     * <p>
     * Useful for verifying interactions that must leave unknown data in a
     * parceled state.
     */
    public static class ExplodingParcelable implements Parcelable {
        public ExplodingParcelable() {
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(42);
        }

        public static final Creator<ExplodingParcelable> CREATOR =
                new Creator<ExplodingParcelable>() {
                    @Override
                    public ExplodingParcelable createFromParcel(Parcel in) {
                        throw new BadParcelableException("exploding!");
                    }

                    @Override
                    public ExplodingParcelable[] newArray(int size) {
                        throw new BadParcelableException("exploding!");
                    }
                };
    }
}
