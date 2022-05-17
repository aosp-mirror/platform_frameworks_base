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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;

/**
 * Parcelable containing compat config overrides by application.
 * @hide
 */
public final class CompatibilityOverridesByPackageConfig implements Parcelable {
    public final Map<String, CompatibilityOverrideConfig> packageNameToOverrides;

    public CompatibilityOverridesByPackageConfig(
            Map<String, CompatibilityOverrideConfig> packageNameToOverrides) {
        this.packageNameToOverrides = packageNameToOverrides;
    }

    private CompatibilityOverridesByPackageConfig(Parcel in) {
        int keyCount = in.readInt();
        packageNameToOverrides = new HashMap<>();
        for (int i = 0; i < keyCount; i++) {
            String key = in.readString();
            packageNameToOverrides.put(key,
                    CompatibilityOverrideConfig.CREATOR.createFromParcel(in));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(packageNameToOverrides.size());
        for (String key : packageNameToOverrides.keySet()) {
            dest.writeString(key);
            packageNameToOverrides.get(key).writeToParcel(dest, /* flags= */ 0);
        }
    }

    public static final Parcelable.Creator<CompatibilityOverridesByPackageConfig> CREATOR =
            new Parcelable.Creator<CompatibilityOverridesByPackageConfig>() {

                @Override
                public CompatibilityOverridesByPackageConfig createFromParcel(Parcel in) {
                    return new CompatibilityOverridesByPackageConfig(in);
                }

                @Override
                public CompatibilityOverridesByPackageConfig[] newArray(int size) {
                    return new CompatibilityOverridesByPackageConfig[size];
                }
            };
}
