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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * Configured rules for merging two {@link Bundle} instances.
 * <p>
 * By default, values from both {@link Bundle} instances are blended together on
 * a key-wise basis, and conflicting value definitions for a key are dropped.
 * <p>
 * Nuanced strategies for handling conflicting value definitions can be applied
 * using {@link #setMergeStrategy(String, int)} and
 * {@link #setDefaultMergeStrategy(int)}.
 * <p>
 * When conflicting values have <em>inconsistent</em> data types (such as trying
 * to merge a {@link String} and a {@link Integer}), both conflicting values are
 * rejected and the key becomes undefined, regardless of the requested strategy.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class BundleMerger implements Parcelable {
    private static final String TAG = "BundleMerger";

    private @Strategy int mDefaultStrategy = STRATEGY_REJECT;

    private final ArrayMap<String, Integer> mStrategies = new ArrayMap<>();

    /**
     * Merge strategy that rejects both conflicting values.
     */
    public static final int STRATEGY_REJECT = 0;

    /**
     * Merge strategy that selects the first of conflicting values.
     */
    public static final int STRATEGY_FIRST = 1;

    /**
     * Merge strategy that selects the last of conflicting values.
     */
    public static final int STRATEGY_LAST = 2;

    /**
     * Merge strategy that selects the "minimum" of conflicting values which are
     * {@link Comparable} with each other.
     */
    public static final int STRATEGY_COMPARABLE_MIN = 3;

    /**
     * Merge strategy that selects the "maximum" of conflicting values which are
     * {@link Comparable} with each other.
     */
    public static final int STRATEGY_COMPARABLE_MAX = 4;

    /**
     * Merge strategy that numerically adds both conflicting values.
     */
    public static final int STRATEGY_NUMBER_ADD = 10;

    /**
     * Merge strategy that numerically increments the first conflicting value by
     * {@code 1} and ignores the last conflicting value.
     */
    public static final int STRATEGY_NUMBER_INCREMENT_FIRST = 20;

    /**
     * Merge strategy that numerically increments the first conflicting value by
     * {@code 1} and also numerically adds both conflicting values.
     */
    public static final int STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD = 25;

    /**
     * Merge strategy that combines conflicting values using a boolean "and"
     * operation.
     */
    public static final int STRATEGY_BOOLEAN_AND = 30;

    /**
     * Merge strategy that combines conflicting values using a boolean "or"
     * operation.
     */
    public static final int STRATEGY_BOOLEAN_OR = 40;

    /**
     * Merge strategy that combines two conflicting array values by appending
     * the last array after the first array.
     */
    public static final int STRATEGY_ARRAY_APPEND = 50;

    /**
     * Merge strategy that combines two conflicting {@link ArrayList} values by
     * appending the last {@link ArrayList} after the first {@link ArrayList}.
     */
    public static final int STRATEGY_ARRAY_LIST_APPEND = 60;

    @IntDef(flag = false, prefix = { "STRATEGY_" }, value = {
            STRATEGY_REJECT,
            STRATEGY_FIRST,
            STRATEGY_LAST,
            STRATEGY_COMPARABLE_MIN,
            STRATEGY_COMPARABLE_MAX,
            STRATEGY_NUMBER_ADD,
            STRATEGY_NUMBER_INCREMENT_FIRST,
            STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD,
            STRATEGY_BOOLEAN_AND,
            STRATEGY_BOOLEAN_OR,
            STRATEGY_ARRAY_APPEND,
            STRATEGY_ARRAY_LIST_APPEND,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Strategy {}

    /**
     * Create a empty set of rules for merging two {@link Bundle} instances.
     */
    public BundleMerger() {
    }

    private BundleMerger(@NonNull Parcel in) {
        mDefaultStrategy = in.readInt();
        final int N = in.readInt();
        for (int i = 0; i < N; i++) {
            mStrategies.put(in.readString(), in.readInt());
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mDefaultStrategy);
        final int N = mStrategies.size();
        out.writeInt(N);
        for (int i = 0; i < N; i++) {
            out.writeString(mStrategies.keyAt(i));
            out.writeInt(mStrategies.valueAt(i));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Configure the default merge strategy to be used when there isn't a
     * more-specific strategy defined for a particular key via
     * {@link #setMergeStrategy(String, int)}.
     */
    public void setDefaultMergeStrategy(@Strategy int strategy) {
        mDefaultStrategy = strategy;
    }

    /**
     * Configure the merge strategy to be used for the given key.
     * <p>
     * Subsequent calls for the same key will overwrite any previously
     * configured strategy.
     */
    public void setMergeStrategy(@NonNull String key, @Strategy int strategy) {
        mStrategies.put(key, strategy);
    }

    /**
     * Return the merge strategy to be used for the given key, as defined by
     * {@link #setMergeStrategy(String, int)}.
     * <p>
     * If no specific strategy has been configured for the given key, this
     * returns {@link #setDefaultMergeStrategy(int)}.
     */
    public @Strategy int getMergeStrategy(@NonNull String key) {
        return (int) mStrategies.getOrDefault(key, mDefaultStrategy);
    }

    /**
     * Return a {@link BinaryOperator} which applies the strategies configured
     * in this object to merge the two given {@link Bundle} arguments.
     */
    public BinaryOperator<Bundle> asBinaryOperator() {
        return this::merge;
    }

    /**
     * Apply the strategies configured in this object to merge the two given
     * {@link Bundle} arguments.
     *
     * @return the merged {@link Bundle} result. If one argument is {@code null}
     *         it will return the other argument. If both arguments are null it
     *         will return {@code null}.
     */
    @SuppressWarnings("deprecation")
    public @Nullable Bundle merge(@Nullable Bundle first, @Nullable Bundle last) {
        if (first == null && last == null) {
            return null;
        }
        if (first == null) {
            first = Bundle.EMPTY;
        }
        if (last == null) {
            last = Bundle.EMPTY;
        }

        // Start by bulk-copying all values without attempting to unpack any
        // custom parcelables; we'll circle back to handle conflicts below
        final Bundle res = new Bundle();
        res.putAll(first);
        res.putAll(last);

        final ArraySet<String> conflictingKeys = new ArraySet<>();
        conflictingKeys.addAll(first.keySet());
        conflictingKeys.retainAll(last.keySet());
        for (int i = 0; i < conflictingKeys.size(); i++) {
            final String key = conflictingKeys.valueAt(i);
            final int strategy = getMergeStrategy(key);
            final Object firstValue = first.get(key);
            final Object lastValue = last.get(key);
            try {
                res.putObject(key, merge(strategy, firstValue, lastValue));
            } catch (Exception e) {
                Log.w(TAG, "Failed to merge key " + key + " with " + firstValue + " and "
                        + lastValue + " using strategy " + strategy, e);
            }
        }
        return res;
    }

    /**
     * Merge the two given values. If only one of the values is defined, it
     * always wins, otherwise the given strategy is applied.
     *
     * @hide
     */
    @VisibleForTesting
    public static @Nullable Object merge(@Strategy int strategy,
            @Nullable Object first, @Nullable Object last) {
        if (first == null) return last;
        if (last == null) return first;

        if (first.getClass() != last.getClass()) {
            throw new IllegalArgumentException("Merging requires consistent classes; first "
                    + first.getClass() + " last " + last.getClass());
        }

        switch (strategy) {
            case STRATEGY_REJECT:
                // Only actually reject when the values are different
                if (Objects.deepEquals(first, last)) {
                    return first;
                } else {
                    return null;
                }
            case STRATEGY_FIRST:
                return first;
            case STRATEGY_LAST:
                return last;
            case STRATEGY_COMPARABLE_MIN:
                return comparableMin(first, last);
            case STRATEGY_COMPARABLE_MAX:
                return comparableMax(first, last);
            case STRATEGY_NUMBER_ADD:
                return numberAdd(first, last);
            case STRATEGY_NUMBER_INCREMENT_FIRST:
                return numberIncrementFirst(first, last);
            case STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD:
                return numberAdd(numberIncrementFirst(first, last), last);
            case STRATEGY_BOOLEAN_AND:
                return booleanAnd(first, last);
            case STRATEGY_BOOLEAN_OR:
                return booleanOr(first, last);
            case STRATEGY_ARRAY_APPEND:
                return arrayAppend(first, last);
            case STRATEGY_ARRAY_LIST_APPEND:
                return arrayListAppend(first, last);
            default:
                throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Object comparableMin(@NonNull Object first, @NonNull Object last) {
        return ((Comparable<Object>) first).compareTo(last) < 0 ? first : last;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Object comparableMax(@NonNull Object first, @NonNull Object last) {
        return ((Comparable<Object>) first).compareTo(last) >= 0 ? first : last;
    }

    private static @NonNull Object numberAdd(@NonNull Object first, @NonNull Object last) {
        if (first instanceof Integer) {
            return ((Integer) first) + ((Integer) last);
        } else if (first instanceof Long) {
            return ((Long) first) + ((Long) last);
        } else if (first instanceof Float) {
            return ((Float) first) + ((Float) last);
        } else if (first instanceof Double) {
            return ((Double) first) + ((Double) last);
        } else {
            throw new IllegalArgumentException("Unable to add " + first.getClass());
        }
    }

    private static @NonNull Number numberIncrementFirst(@NonNull Object first,
            @NonNull Object last) {
        if (first instanceof Integer) {
            return ((Integer) first) + 1;
        } else if (first instanceof Long) {
            return ((Long) first) + 1L;
        } else {
            throw new IllegalArgumentException("Unable to add " + first.getClass());
        }
    }

    private static @NonNull Object booleanAnd(@NonNull Object first, @NonNull Object last) {
        return ((Boolean) first) && ((Boolean) last);
    }

    private static @NonNull Object booleanOr(@NonNull Object first, @NonNull Object last) {
        return ((Boolean) first) || ((Boolean) last);
    }

    private static @NonNull Object arrayAppend(@NonNull Object first, @NonNull Object last) {
        if (!first.getClass().isArray()) {
            throw new IllegalArgumentException("Unable to append " + first.getClass());
        }
        final Class<?> clazz = first.getClass().getComponentType();
        final int firstLength = Array.getLength(first);
        final int lastLength = Array.getLength(last);
        final Object res = Array.newInstance(clazz, firstLength + lastLength);
        System.arraycopy(first, 0, res, 0, firstLength);
        System.arraycopy(last, 0, res, firstLength, lastLength);
        return res;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Object arrayListAppend(@NonNull Object first, @NonNull Object last) {
        if (!(first instanceof ArrayList)) {
            throw new IllegalArgumentException("Unable to append " + first.getClass());
        }
        final ArrayList<Object> firstList = (ArrayList<Object>) first;
        final ArrayList<Object> lastList = (ArrayList<Object>) last;
        final ArrayList<Object> res = new ArrayList<>(firstList.size() + lastList.size());
        res.addAll(firstList);
        res.addAll(lastList);
        return res;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BundleMerger> CREATOR =
            new Parcelable.Creator<BundleMerger>() {
                @Override
                public BundleMerger createFromParcel(Parcel in) {
                    return new BundleMerger(in);
                }

                @Override
                public BundleMerger[] newArray(int size) {
                    return new BundleMerger[size];
                }
            };
}
