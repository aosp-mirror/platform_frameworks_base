/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class Utils {
    private static final String TAG = "BroadcastRadio.utils";

    static void writeStringMap(@NonNull Parcel dest, @Nullable Map<String, String> map) {
        if (map == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeString(entry.getValue());
        }
    }

    static @NonNull Map<String, String> readStringMap(@NonNull Parcel in) {
        int size = in.readInt();
        Map<String, String> map = new HashMap<>();
        while (size-- > 0) {
            String key = in.readString();
            String value = in.readString();
            map.put(key, value);
        }
        return map;
    }

    static void writeStringIntMap(@NonNull Parcel dest, @Nullable Map<String, Integer> map) {
        if (map == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(map.size());
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeInt(entry.getValue());
        }
    }

    static @NonNull Map<String, Integer> readStringIntMap(@NonNull Parcel in) {
        int size = in.readInt();
        Map<String, Integer> map = new HashMap<>();
        while (size-- > 0) {
            String key = in.readString();
            int value = in.readInt();
            map.put(key, value);
        }
        return map;
    }

    static <T extends Parcelable> void writeSet(@NonNull Parcel dest, @Nullable Set<T> set) {
        if (set == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(set.size());
        set.stream().forEach(elem -> dest.writeTypedObject(elem, 0));
    }

    static <T> Set<T> createSet(@NonNull Parcel in, Parcelable.Creator<T> c) {
        int size = in.readInt();
        Set<T> set = new HashSet<>();
        while (size-- > 0) {
            set.add(in.readTypedObject(c));
        }
        return set;
    }

    static void writeIntSet(@NonNull Parcel dest, @Nullable Set<Integer> set) {
        if (set == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(set.size());
        set.stream().forEach(elem -> dest.writeInt(Objects.requireNonNull(elem)));
    }

    static Set<Integer> createIntSet(@NonNull Parcel in) {
        return createSet(in, new Parcelable.Creator<Integer>() {
            public Integer createFromParcel(Parcel in) {
                return in.readInt();
            }

            public Integer[] newArray(int size) {
                return new Integer[size];
            }
        });
    }

    static <T extends Parcelable> void writeTypedCollection(@NonNull Parcel dest,
            @Nullable Collection<T> coll) {
        ArrayList<T> list = null;
        if (coll != null) {
            if (coll instanceof ArrayList) {
                list = (ArrayList) coll;
            } else {
                list = new ArrayList<>(coll);
            }
        }
        dest.writeTypedList(list);
    }

    static void close(ICloseHandle handle) {
        try {
            handle.close();
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }
}
