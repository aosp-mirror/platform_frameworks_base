/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.SystemClock;

import java.util.Objects;

/**
 * A value with an associated reference time. The reference time will typically be provided by the
 * elapsed realtime clock. The elapsed realtime clock can be obtained using methods like
 * {@link SystemClock#elapsedRealtime()} or {@link SystemClock#elapsedRealtimeClock()}.
 * If a suitable clock is used the reference time can be used to identify the age of a value or
 * ordering between values.
 *
 * <p>To read and write a timestamped value from / to a Parcel see
 * {@link #readFromParcel(Parcel, ClassLoader, Class)} and
 * {@link #writeToParcel(Parcel, TimestampedValue)}.
 *
 * @param <T> the type of the value with an associated timestamp
 * @hide
 */
public final class TimestampedValue<T> {
    private final long mReferenceTimeMillis;
    private final T mValue;

    public TimestampedValue(long referenceTimeMillis, T value) {
        mReferenceTimeMillis = referenceTimeMillis;
        mValue = value;
    }

    public long getReferenceTimeMillis() {
        return mReferenceTimeMillis;
    }

    public T getValue() {
        return mValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimestampedValue<?> that = (TimestampedValue<?>) o;
        return mReferenceTimeMillis == that.mReferenceTimeMillis
                && Objects.equals(mValue, that.mValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mReferenceTimeMillis, mValue);
    }

    @Override
    public String toString() {
        return "TimestampedValue{"
                + "mReferenceTimeMillis=" + mReferenceTimeMillis
                + ", mValue=" + mValue
                + '}';
    }

    /**
     * Read a {@link TimestampedValue} from a parcel that was stored using
     * {@link #writeToParcel(Parcel, TimestampedValue)}.
     *
     * <p>The marshalling/unmarshalling of the value relies upon {@link Parcel#writeValue(Object)}
     * and {@link Parcel#readValue(ClassLoader)} and so this method can only be used with types
     * supported by those methods.
     *
     * @param in the Parcel to read from
     * @param classLoader the ClassLoader to pass to {@link Parcel#readValue(ClassLoader)}
     * @param valueClass the expected type of the value, typically the same as {@code <T>} but can
     *     also be a subclass
     * @throws RuntimeException if the value read is not compatible with {@code valueClass} or the
     *     object could not be read
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static <T> TimestampedValue<T> readFromParcel(
            @NonNull Parcel in, @Nullable ClassLoader classLoader, Class<? extends T> valueClass) {
        long referenceTimeMillis = in.readLong();
        T value = (T) in.readValue(classLoader);
        // Equivalent to static code: if (!(value.getClass() instanceof {valueClass})) {
        if (value != null && !valueClass.isAssignableFrom(value.getClass())) {
            throw new RuntimeException("Value was of type " + value.getClass()
                    + " is not assignable to " + valueClass);
        }
        return new TimestampedValue<>(referenceTimeMillis, value);
    }

    /**
     * Write a {@link TimestampedValue} to a parcel so that it can be read using
     * {@link #readFromParcel(Parcel, ClassLoader, Class)}.
     *
     * <p>The marshalling/unmarshalling of the value relies upon {@link Parcel#writeValue(Object)}
     * and {@link Parcel#readValue(ClassLoader)} and so this method can only be used with types
     * supported by those methods.
     *
     * @param dest the Parcel
     * @param timestampedValue the value
     * @throws RuntimeException if the value could not be written to the Parcel
     */
    public static void writeToParcel(
            @NonNull Parcel dest, @NonNull TimestampedValue<?> timestampedValue) {
        dest.writeLong(timestampedValue.mReferenceTimeMillis);
        dest.writeValue(timestampedValue.mValue);
    }

    /**
     * Returns the difference in milliseconds between two instance's reference times.
     */
    public static long referenceTimeDifference(
            @NonNull TimestampedValue<?> one, @NonNull TimestampedValue<?> two) {
        return one.mReferenceTimeMillis - two.mReferenceTimeMillis;
    }
}
