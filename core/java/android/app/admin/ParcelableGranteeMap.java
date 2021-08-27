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

package android.app.admin;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Map;
import java.util.Set;

/**
 * Class for marshalling keypair grantees for a given KeyChain key via Binder.
 *
 * @hide
 */
public class ParcelableGranteeMap implements Parcelable {

    private final Map<Integer, Set<String>> mPackagesByUid;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPackagesByUid.size());
        for (final Map.Entry<Integer, Set<String>> uidEntry : mPackagesByUid.entrySet()) {
            dest.writeInt(uidEntry.getKey());
            dest.writeStringArray(uidEntry.getValue().toArray(new String[0]));
        }
    }

    public static final @NonNull Parcelable.Creator<ParcelableGranteeMap> CREATOR =
            new Parcelable.Creator<ParcelableGranteeMap>() {
                @Override
                public ParcelableGranteeMap createFromParcel(Parcel source) {
                    final Map<Integer, Set<String>> packagesByUid = new ArrayMap<>();
                    final int numUids = source.readInt();
                    for (int i = 0; i < numUids; i++) {
                        final int uid = source.readInt();
                        final String[] pkgs = source.readStringArray();
                        packagesByUid.put(uid, new ArraySet<>(pkgs));
                    }
                    return new ParcelableGranteeMap(packagesByUid);
                }

                @Override
                public ParcelableGranteeMap[] newArray(int size) {
                    return new ParcelableGranteeMap[size];
                }
            };

    /**
     * Creates an instance holding a reference (not a copy) to the given map.
     */
    public ParcelableGranteeMap(@NonNull Map<Integer, Set<String>> packagesByUid) {
        mPackagesByUid = packagesByUid;
    }

    /**
     * Returns a reference (not a copy) to the stored map.
     */
    @NonNull
    public Map<Integer, Set<String>> getPackagesByUid() {
        return mPackagesByUid;
    }
}
