/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.tv.tuner.TunerUtils;

/**
 * Filter Settings for Section data according to ISO/IEC 13818-1.
 *
 * @hide
 */
@SystemApi
public abstract class SectionSettings extends Settings {
    final boolean mCrcEnabled;
    final boolean mIsRepeat;
    final boolean mIsRaw;

    SectionSettings(int mainType, boolean crcEnabled, boolean isRepeat, boolean isRaw) {
        super(TunerUtils.getFilterSubtype(mainType, Filter.SUBTYPE_SECTION));
        mCrcEnabled = crcEnabled;
        mIsRepeat = isRepeat;
        mIsRaw = isRaw;
    }

    /**
     * Returns whether the filter enables CRC (Cyclic redundancy check) and discards data which
     * doesn't pass the check.
     */
    public boolean isCrcEnabled() {
        return mCrcEnabled;
    }
    /**
     * Returns whether the filter repeats the data with the same version.
     */
    public boolean isRepeat() {
        return mIsRepeat;
    }
    /**
     * Returns whether the filter sends {@link FilterCallback#onFilterStatusChanged} instead of
     * {@link FilterCallback#onFilterEvent}.
     */
    public boolean isRaw() {
        return mIsRaw;
    }

    /**
     * Builder for {@link SectionSettings}.
     *
     * @param <T> The subclass to be built.
     */
    public abstract static class Builder<T extends Builder<T>> {
        final int mMainType;
        boolean mCrcEnabled;
        boolean mIsRepeat;
        boolean mIsRaw;

        Builder(int mainType) {
            mMainType = mainType;
        }

        /**
         * Sets whether the filter enables CRC (Cyclic redundancy check) and discards data which
         * doesn't pass the check.
         */
        @NonNull
        public T setCrcEnabled(boolean crcEnabled) {
            mCrcEnabled = crcEnabled;
            return self();
        }
        /**
         * Sets whether the filter repeats the data with the same version.
         */
        @NonNull
        public T setRepeat(boolean isRepeat) {
            mIsRepeat = isRepeat;
            return self();
        }
        /**
         * Sets whether the filter send onFilterStatus instead of
         * {@link FilterCallback#onFilterEvent}.
         */
        @NonNull
        public T setRaw(boolean isRaw) {
            mIsRaw = isRaw;
            return self();
        }

        /* package */ abstract T self();
    }
}
