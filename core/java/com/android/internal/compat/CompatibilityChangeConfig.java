/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.compat;


import android.compat.Compatibility.ChangeConfig;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashSet;
import java.util.Set;

/**
 * Parcelable containing compat config overrides for a given application.
 * @hide
 */
public final class CompatibilityChangeConfig implements Parcelable {
    private final ChangeConfig mChangeConfig;

    public CompatibilityChangeConfig(ChangeConfig changeConfig) {
        mChangeConfig = changeConfig;
    }

    /**
     * Changes forced to be enabled.
     */
    public Set<Long> enabledChanges() {
        return mChangeConfig.getEnabledSet();
    }

    /**
     * Changes forced to be disabled.
     */
    public Set<Long> disabledChanges() {
        return mChangeConfig.getDisabledSet();
    }

    /**
     * Returns if a change is enabled or disabled in this config.
     */
    public boolean isChangeEnabled(long changeId) {
        if (mChangeConfig.isForceEnabled(changeId)) {
            return true;
        } else if (mChangeConfig.isForceDisabled(changeId)) {
            return false;
        }
        throw new IllegalStateException("Change " + changeId + " is not defined.");
    }

    private CompatibilityChangeConfig(Parcel in) {
        long[] enabledArray = in.createLongArray();
        long[] disabledArray = in.createLongArray();
        Set<Long> enabled = toLongSet(enabledArray);
        Set<Long> disabled = toLongSet(disabledArray);
        mChangeConfig = new ChangeConfig(enabled, disabled);
    }

    private static Set<Long> toLongSet(long[] values) {
        Set<Long> ret = new HashSet<>();
        for (long value: values) {
            ret.add(value);
        }
        return ret;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        long[] enabled = mChangeConfig.getEnabledChangesArray();
        long[] disabled = mChangeConfig.getDisabledChangesArray();

        dest.writeLongArray(enabled);
        dest.writeLongArray(disabled);
    }

    public static final Parcelable.Creator<CompatibilityChangeConfig> CREATOR =
            new Parcelable.Creator<CompatibilityChangeConfig>() {

                @Override
                public CompatibilityChangeConfig createFromParcel(Parcel in) {
                    return new CompatibilityChangeConfig(in);
                }

                @Override
                public CompatibilityChangeConfig[] newArray(int size) {
                    return new CompatibilityChangeConfig[size];
                }
            };
}
