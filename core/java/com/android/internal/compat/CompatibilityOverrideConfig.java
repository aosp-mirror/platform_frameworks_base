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

package com.android.internal.compat;


import android.app.compat.PackageOverride;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;

/**
 * Parcelable containing compat config overrides for a given application.
 * @hide
 */
public final class CompatibilityOverrideConfig implements Parcelable {
    public final Map<Long, PackageOverride> overrides;

    public CompatibilityOverrideConfig(Map<Long, PackageOverride> overrides) {
        this.overrides = overrides;
    }

    private CompatibilityOverrideConfig(Parcel in) {
        int keyCount = in.readInt();
        overrides = new HashMap<>();
        for (int i = 0; i < keyCount; i++) {
            long key = in.readLong();
            overrides.put(key, PackageOverride.createFromParcel(in));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(overrides.size());
        for (Long key : overrides.keySet()) {
            dest.writeLong(key);
            overrides.get(key).writeToParcel(dest);
        }
    }

    public static final Creator<CompatibilityOverrideConfig> CREATOR =
            new Creator<CompatibilityOverrideConfig>() {

                @Override
                public CompatibilityOverrideConfig createFromParcel(Parcel in) {
                    return new CompatibilityOverrideConfig(in);
                }

                @Override
                public CompatibilityOverrideConfig[] newArray(int size) {
                    return new CompatibilityOverrideConfig[size];
                }
            };
}
