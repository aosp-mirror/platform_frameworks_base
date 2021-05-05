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

import java.util.HashSet;
import java.util.Set;

/**
 * Parcelable containing compat config change IDs for which to remove overrides for a given
 * application.
 * @hide
 */
public final class CompatibilityOverridesToRemoveConfig implements Parcelable {
    public final Set<Long> changeIds;

    public CompatibilityOverridesToRemoveConfig(Set<Long> changeIds) {
        this.changeIds = changeIds;
    }

    private CompatibilityOverridesToRemoveConfig(Parcel in) {
        int keyCount = in.readInt();
        changeIds = new HashSet<>();
        for (int i = 0; i < keyCount; i++) {
            changeIds.add(in.readLong());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(changeIds.size());
        for (Long changeId : changeIds) {
            dest.writeLong(changeId);
        }
    }

    public static final Creator<CompatibilityOverridesToRemoveConfig> CREATOR =
            new Creator<CompatibilityOverridesToRemoveConfig>() {

                @Override
                public CompatibilityOverridesToRemoveConfig createFromParcel(Parcel in) {
                    return new CompatibilityOverridesToRemoveConfig(in);
                }

                @Override
                public CompatibilityOverridesToRemoveConfig[] newArray(int size) {
                    return new CompatibilityOverridesToRemoveConfig[size];
                }
            };
}
