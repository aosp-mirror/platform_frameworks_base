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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Objects;

/**
 * A value with an associated reference time. The reference time will typically be provided by the
 * elapsed realtime clock. The elapsed realtime clock can be obtained using methods like
 * {@link SystemClock#elapsedRealtime()} or {@link SystemClock#elapsedRealtimeClock()}.
 * If a suitable clock is used the reference time can be used to identify the age of a value or
 * ordering between values.
 *
 * <p>This class implements {@link Parcelable} for convenience but instances will only actually be
 * parcelable if the value type held is {@code null}, {@link Parcelable}, or one of the other types
 * supported by {@link Parcel#writeValue(Object)} / {@link Parcel#readValue(ClassLoader)}.
 *
 * @param <T> the type of the value with an associated timestamp
 * @hide
 */
public final class TimestampedValue<T> implements Parcelable {
    private final long mReferenceTimeMillis;
    @Nullable
    private final T mValue;

    public TimestampedValue(long referenceTimeMillis, @Nullable T value) {
        mReferenceTimeMillis = referenceTimeMillis;
        mValue = value;
    }

    /** Returns the reference time value. See {@link TimestampedValue} for more information. */
    public long getReferenceTimeMillis() {
        return mReferenceTimeMillis;
    }

    /**
     * Returns the value associated with the timestamp. See {@link TimestampedValue} for more
     * information.
     */
    @Nullable
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
     * Returns the difference in milliseconds between two instance's reference times.
     */
    public static long referenceTimeDifference(
            @NonNull TimestampedValue<?> one, @NonNull TimestampedValue<?> two) {
        return one.mReferenceTimeMillis - two.mReferenceTimeMillis;
    }

    /** @hide */
    public static final @NonNull Parcelable.Creator<TimestampedValue<?>> CREATOR =
            new Parcelable.ClassLoaderCreator<TimestampedValue<?>>() {

                @Override
                public TimestampedValue<?> createFromParcel(@NonNull Parcel source) {
                    return createFromParcel(source, null);
                }

                @Override
                public TimestampedValue<?> createFromParcel(
                        @NonNull Parcel source, @Nullable ClassLoader classLoader) {
                    long referenceTimeMillis = source.readLong();
                    Object value = source.readValue(classLoader);
                    return new TimestampedValue<>(referenceTimeMillis, value);
                }

                @Override
                public TimestampedValue[] newArray(int size) {
                    return new TimestampedValue[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mReferenceTimeMillis);
        dest.writeValue(mValue);
    }
}
