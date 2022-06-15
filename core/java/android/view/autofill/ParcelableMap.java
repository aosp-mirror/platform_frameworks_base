/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.autofill;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;

/**
 * A parcelable HashMap for {@link AutofillId} and {@link AutofillValue}
 *
 * {@hide}
 */
class ParcelableMap extends HashMap<AutofillId, AutofillValue> implements Parcelable {
    ParcelableMap(int size) {
        super(size);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(size());

        for (Map.Entry<AutofillId, AutofillValue> entry : entrySet()) {
            dest.writeParcelable(entry.getKey(), 0);
            dest.writeParcelable(entry.getValue(), 0);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ParcelableMap> CREATOR =
            new Parcelable.Creator<ParcelableMap>() {
                @Override
                public ParcelableMap createFromParcel(Parcel source) {
                    int size = source.readInt();

                    ParcelableMap map = new ParcelableMap(size);

                    for (int i = 0; i < size; i++) {
                        AutofillId key = source.readParcelable(null);
                        AutofillValue value = source.readParcelable(null);

                        map.put(key, value);
                    }

                    return map;
                }

                @Override
                public ParcelableMap[] newArray(int size) {
                    return new ParcelableMap[size];
                }
            };
}
