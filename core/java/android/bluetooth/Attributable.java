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

package android.bluetooth;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.AttributionSource;

import java.util.List;

/**
 * Marker interface for a class which can have an {@link AttributionSource}
 * assigned to it; these are typically {@link android.os.Parcelable} classes
 * which need to be updated after crossing Binder transaction boundaries.
 *
 * @hide
 */
public interface Attributable {
    void setAttributionSource(@NonNull AttributionSource attributionSource);

    static @Nullable <T extends Attributable> T setAttributionSource(
            @Nullable T attributable,
            @NonNull AttributionSource attributionSource) {
        if (attributable != null) {
            attributable.setAttributionSource(attributionSource);
        }
        return attributable;
    }

    static @Nullable <T extends Attributable> List<T> setAttributionSource(
            @Nullable List<T> attributableList,
            @NonNull AttributionSource attributionSource) {
        if (attributableList != null) {
            final int size = attributableList.size();
            for (int i = 0; i < size; i++) {
                setAttributionSource(attributableList.get(i), attributionSource);
            }
        }
        return attributableList;
    }
}
