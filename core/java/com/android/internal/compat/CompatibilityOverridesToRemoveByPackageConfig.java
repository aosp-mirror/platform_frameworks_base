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
 * Parcelable containing compat config change IDs for which to remove overrides by application.
 *
 * <p>This class is separate from CompatibilityOverridesByPackageConfig since we only need change
 * IDs.
 * @hide
 */
public final class CompatibilityOverridesToRemoveByPackageConfig implements Parcelable {
    public final Map<String, CompatibilityOverridesToRemoveConfig> packageNameToOverridesToRemove;

    public CompatibilityOverridesToRemoveByPackageConfig(
            Map<String, CompatibilityOverridesToRemoveConfig> packageNameToOverridesToRemove) {
        this.packageNameToOverridesToRemove = packageNameToOverridesToRemove;
    }

    private CompatibilityOverridesToRemoveByPackageConfig(Parcel in) {
        int keyCount = in.readInt();
        packageNameToOverridesToRemove = new HashMap<>();
        for (int i = 0; i < keyCount; i++) {
            String key = in.readString();
            packageNameToOverridesToRemove.put(key,
                    CompatibilityOverridesToRemoveConfig.CREATOR.createFromParcel(in));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(packageNameToOverridesToRemove.size());
        for (String key : packageNameToOverridesToRemove.keySet()) {
            dest.writeString(key);
            packageNameToOverridesToRemove.get(key).writeToParcel(dest, /* flags= */ 0);
        }
    }

    public static final Parcelable.Creator<CompatibilityOverridesToRemoveByPackageConfig> CREATOR =
            new Parcelable.Creator<CompatibilityOverridesToRemoveByPackageConfig>() {

                @Override
                public CompatibilityOverridesToRemoveByPackageConfig createFromParcel(Parcel in) {
                    return new CompatibilityOverridesToRemoveByPackageConfig(in);
                }

                @Override
                public CompatibilityOverridesToRemoveByPackageConfig[] newArray(int size) {
                    return new CompatibilityOverridesToRemoveByPackageConfig[size];
                }
            };
}
