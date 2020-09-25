/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.os;

import java.util.List;

public class Parcel {
    public void writeString(String val) {
        throw new UnsupportedOperationException();
    }
    public void writeString8(String val) {
        throw new UnsupportedOperationException();
    }
    public final void writeStringArray(String[] val) {
        throw new UnsupportedOperationException();
    }
    public final void writeString8Array(String[] val) {
        throw new UnsupportedOperationException();
    }

    public final void writeValue(Object v) {
        throw new UnsupportedOperationException();
    }
    public final void writeParcelable(Parcelable p, int flags) {
        throw new UnsupportedOperationException();
    }

    public final void writeList(List val) {
        throw new UnsupportedOperationException();
    }
    public final <T extends Parcelable> void writeParcelableList(List<T> val, int flags) {
        throw new UnsupportedOperationException();
    }
    public <T extends Parcelable> void writeTypedList(List<T> val, int flags) {
        throw new UnsupportedOperationException();
    }
    public final <T extends Parcelable> void writeParcelableArray(T[] value, int flags) {
        throw new UnsupportedOperationException();
    }
    public final <T extends Parcelable> void writeTypedArray(T[] val, int flags) {
        throw new UnsupportedOperationException();
    }
}
