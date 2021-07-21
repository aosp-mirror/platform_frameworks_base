/*
 * Copyright 2020 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;

/**
 * This is a copied version of MediaParceledListSlice in framework with hidden API usages removed,
 * and also with some lint error fixed.
 *
 * Transfer a large list of Parcelable objects across an IPC.  Splits into
 * multiple transactions if needed.
 *
 * TODO: Remove this from @SystemApi once all the MediaSession related classes are moved
 *       to apex (or ParceledListSlice moved to apex). This class is temporaily added to system API
 *       for moving classes step by step.
 *
 * @param <T> The type of the elements in the list.
 * @see BaseMediaParceledListSlice
 * @deprecated This is temporary marked as @SystemApi. Should be removed from the API surface.
 * @hide
 */
@Deprecated
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class MediaParceledListSlice<T extends Parcelable>
        extends BaseMediaParceledListSlice<T> {
    public MediaParceledListSlice(@NonNull List<T> list) {
        super(list);
    }

    private MediaParceledListSlice(Parcel in, ClassLoader loader) {
        super(in, loader);
    }

    @NonNull
    public static <T extends Parcelable> MediaParceledListSlice<T> emptyList() {
        return new MediaParceledListSlice<T>(Collections.<T> emptyList());
    }

    @Override
    public int describeContents() {
        int contents = 0;
        final List<T> list = getList();
        for (int i=0; i<list.size(); i++) {
            contents |= list.get(i).describeContents();
        }
        return contents;
    }

    @Override
    void writeElement(T parcelable, Parcel dest, int callFlags) {
        parcelable.writeToParcel(dest, callFlags);
    }

    @Override
    void writeParcelableCreator(T parcelable, Parcel dest) {
        dest.writeParcelableCreator((Parcelable) parcelable);
    }

    @Override
    Parcelable.Creator<?> readParcelableCreator(Parcel from, ClassLoader loader) {
        return from.readParcelableCreator(loader);
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public static final Parcelable.ClassLoaderCreator<MediaParceledListSlice> CREATOR =
            new Parcelable.ClassLoaderCreator<MediaParceledListSlice>() {
        public MediaParceledListSlice createFromParcel(Parcel in) {
            return new MediaParceledListSlice(in, null);
        }

        @Override
        public MediaParceledListSlice createFromParcel(Parcel in, ClassLoader loader) {
            return new MediaParceledListSlice(in, loader);
        }

        @Override
        public MediaParceledListSlice[] newArray(int size) {
            return new MediaParceledListSlice[size];
        }
    };
}
