/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.compat;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An app compat override applied to a given package and change id pairing.
 *
 * A package override contains a list of version ranges with the desired boolean value of
 * the override for the app in this version range. Ranges can be open ended in either direction.
 * An instance of PackageOverride gets created via {@link Builder} and is immutable once created.
 *
 * @hide
 */
@SystemApi
public final class PackageOverride {

    /** @hide */
    @IntDef({
            VALUE_UNDEFINED,
            VALUE_ENABLED,
            VALUE_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EvaluatedOverride {
    }

    /**
     * Return value of {@link #evaluate(long)} and {@link #evaluateForAllVersions()} indicating that
     * this PackageOverride does not define the value of the override for the given version.
     * @hide
     */
    public static final int VALUE_UNDEFINED = 0;
    /**
     * Return value of {@link #evaluate(long)} and {@link #evaluateForAllVersions()} indicating that
     * the override evaluates to {@code true} for the given version.
     * @hide
     */
    public static final int VALUE_ENABLED = 1;
    /**
     * Return value of {@link #evaluate(long)} and {@link #evaluateForAllVersions()} indicating that
     * the override evaluates to {@code fakse} for the given version.
     * @hide
     */
    public static final int VALUE_DISABLED = 2;

    private final long mMinVersionCode;
    private final long mMaxVersionCode;
    private final boolean mEnabled;

    private PackageOverride(long minVersionCode,
            long maxVersionCode,
            boolean enabled) {
        this.mMinVersionCode = minVersionCode;
        this.mMaxVersionCode = maxVersionCode;
        this.mEnabled = enabled;
    }

    /**
     * Evaluate the override for the given {@code versionCode}. If no override is defined for
     * the specified version code, {@link #VALUE_UNDEFINED} is returned.
     * @hide
     */
    public @EvaluatedOverride int evaluate(long versionCode) {
        if (versionCode >= mMinVersionCode && versionCode <= mMaxVersionCode) {
            return mEnabled ? VALUE_ENABLED : VALUE_DISABLED;
        }
        return VALUE_UNDEFINED;
    }

    /**
     * Evaluate the override independent of version code, i.e. only return an evaluated value if
     * this range covers all versions, otherwise {@link #VALUE_UNDEFINED} is returned.
     * @hide
     */
    public int evaluateForAllVersions() {
        if (mMinVersionCode == Long.MIN_VALUE && mMaxVersionCode == Long.MAX_VALUE) {
            return mEnabled ? VALUE_ENABLED : VALUE_DISABLED;
        }
        return VALUE_UNDEFINED;
    }

    /** Returns the minimum version code the override applies to. */
    public long getMinVersionCode() {
        return mMinVersionCode;
    }

    /** Returns the minimum version code the override applies from. */
    public long getMaxVersionCode() {
        return mMaxVersionCode;
    }

    /** Returns the enabled value for the override. */
    public boolean isEnabled() {
        return mEnabled;
    }

    /** @hide */
    public void writeToParcel(Parcel dest) {
        dest.writeLong(mMinVersionCode);
        dest.writeLong(mMaxVersionCode);
        dest.writeBoolean(mEnabled);
    }

    /** @hide */
    public static PackageOverride createFromParcel(Parcel in) {
        return new PackageOverride(in.readLong(), in.readLong(), in.readBoolean());
    }

    /** @hide */
    @Override
    public String toString() {
        if (mMinVersionCode == Long.MIN_VALUE && mMaxVersionCode == Long.MAX_VALUE) {
            return Boolean.toString(mEnabled);
        }
        return String.format("[%d,%d,%b]", mMinVersionCode, mMaxVersionCode, mEnabled);
    }

    /**
     * Builder to construct a PackageOverride.
     */
    public static final class Builder {
        private long mMinVersionCode = Long.MIN_VALUE;
        private long mMaxVersionCode = Long.MAX_VALUE;
        private boolean mEnabled;

        /**
         * Sets the minimum version code the override should apply from.
         *
         * default value: {@code Long.MIN_VALUE}.
         */
        @NonNull
        public Builder setMinVersionCode(long minVersionCode) {
            mMinVersionCode = minVersionCode;
            return this;
        }

        /**
         * Sets the maximum version code the override should apply to.
         *
         * default value: {@code Long.MAX_VALUE}.
         */
        @NonNull
        public Builder setMaxVersionCode(long maxVersionCode) {
            mMaxVersionCode = maxVersionCode;
            return this;
        }

        /**
         * Sets whether the override should be enabled for the given version range.
         *
         * default value: {@code false}.
         */
        @NonNull
        public Builder setEnabled(boolean enabled) {
            mEnabled = enabled;
            return this;
        }

        /**
         * Build the {@link PackageOverride}.
         *
         * @throws IllegalArgumentException if {@code minVersionCode} is larger than
         *                                  {@code maxVersionCode}.
         */
        @NonNull
        public PackageOverride build() {
            if (mMinVersionCode > mMaxVersionCode) {
                throw new IllegalArgumentException("minVersionCode must not be larger than "
                        + "maxVersionCode");
            }
            return new PackageOverride(mMinVersionCode, mMaxVersionCode, mEnabled);
        }
    };
}
