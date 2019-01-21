/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.shared;

import android.annotation.NonNull;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;

/**
 * Utility methods to help convert to/from stable parcelables.
 * @hide
 */
public final class ParcelableUtil {
    // Below methods could be implemented easily with streams, but streams are frowned upon in
    // frameworks code.

    /**
     * Convert a list of BaseType items to an array of ParcelableType items using the specified
     * converter function.
     */
    public static <ParcelableType, BaseType> ParcelableType[] toParcelableArray(
            @NonNull Collection<BaseType> base,
            @NonNull Function<BaseType, ParcelableType> conv,
            @NonNull Class<ParcelableType> parcelClass) {
        final ParcelableType[] out = (ParcelableType[]) Array.newInstance(parcelClass, base.size());
        int i = 0;
        for (BaseType b : base) {
            out[i] = conv.apply(b);
            i++;
        }
        return out;
    }

    /**
     * Convert an array of ParcelableType items to a list of BaseType items using the specified
     * converter function.
     */
    public static <ParcelableType, BaseType> ArrayList<BaseType> fromParcelableArray(
            @NonNull ParcelableType[] parceled, @NonNull Function<ParcelableType, BaseType> conv) {
        final ArrayList<BaseType> out = new ArrayList<>(parceled.length);
        for (ParcelableType t : parceled) {
            out.add(conv.apply(t));
        }
        return out;
    }
}
