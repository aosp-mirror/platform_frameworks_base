/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.*;

import android.hardware.camera2.utils.HashCodeHelpers;

/**
 * Immutable class for describing the range of two numeric values.
 * <p>
 * A range (or "interval") defines the inclusive boundaries around a contiguous span of
 * values of some {@link Comparable} type; for example,
 * "integers from 1 to 100 inclusive."
 * </p>
 * <p>
 * All ranges are bounded, and the left side of the range is always {@code >=}
 * the right side of the range.
 * </p>
 *
 * <p>Although the implementation itself is immutable, there is no restriction that objects
 * stored must also be immutable. If mutable objects are stored here, then the range
 * effectively becomes mutable. </p>
 */
public final class Range<T extends Comparable<? super T>> {
    /**
     * Create a new immutable range.
     *
     * <p>
     * The endpoints are {@code [lower, upper]}; that
     * is the range is bounded. {@code lower} must be {@link Comparable#compareTo lesser or equal}
     * to {@code upper}.
     * </p>
     *
     * @param lower The lower endpoint (inclusive)
     * @param upper The upper endpoint (inclusive)
     *
     * @throws NullPointerException if {@code lower} or {@code upper} is {@code null}
     */
    public Range(final T lower, final T upper) {
        mLower = checkNotNull(lower, "lower must not be null");
        mUpper = checkNotNull(upper, "upper must not be null");

        if (lower.compareTo(upper) > 0) {
            throw new IllegalArgumentException("lower must be less than or equal to upper");
        }
    }

    /**
     * Create a new immutable range, with the argument types inferred.
     *
     * <p>
     * The endpoints are {@code [lower, upper]}; that
     * is the range is bounded. {@code lower} must be {@link Comparable#compareTo lesser or equal}
     * to {@code upper}.
     * </p>
     *
     * @param lower The lower endpoint (inclusive)
     * @param upper The upper endpoint (inclusive)
     *
     * @throws NullPointerException if {@code lower} or {@code upper} is {@code null}
     */
    public static <T extends Comparable<? super T>> Range<T> create(final T lower, final T upper) {
        return new Range<T>(lower, upper);
    }

    /**
     * Get the lower endpoint.
     *
     * @return a non-{@code null} {@code T} reference
     */
    public T getLower() {
        return mLower;
    }

    /**
     * Get the upper endpoint.
     *
     * @return a non-{@code null} {@code T} reference
     */
    public T getUpper() {
        return mUpper;
    }

    /**
     * Checks if the {@code value} is within the bounds of this range.
     *
     * <p>A value is considered to be within this range if it's {@code >=} then
     * the lower endpoint <i>and</i> {@code <=} to the upper endpoint (using the {@link Comparable}
     * interface.</p>
     *
     * @param value a non-{@code null} {@code T} reference
     * @return {@code true} if the value is within this inclusive range, {@code false} otherwise
     *
     * @throws NullPointerException if {@code value} was {@code null}
     */
    public boolean inRange(T value) {
        checkNotNull(value, "value must not be null");

        boolean gteLower = value.compareTo(mLower) >= 0;
        boolean lteUpper  = value.compareTo(mUpper) <= 0;

        return gteLower && lteUpper;
    }

    /**
     * Compare two ranges for equality.
     *
     * <p>A range is considered equal if and only if both the lower and upper endpoints
     * are also equal.</p>
     *
     * @return {@code true} if the ranges are equal, {@code false} otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof Range) {
            @SuppressWarnings("rawtypes")
            Range other = (Range) obj;
            return mLower.equals(other.mLower) && mUpper.equals(other.mUpper);
        }
        return false;
    }

    /**
     * Return the range as a string representation {@code "[lower, upper]"}.
     *
     * @return string representation of the range
     */
    @Override
    public String toString() {
        return String.format("[%s, %s]", mLower, mUpper);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mLower, mUpper);
    }

    private final T mLower;
    private final T mUpper;
};
