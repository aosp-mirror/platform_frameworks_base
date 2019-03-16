/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/** @hide */
public final class Helper {

    // Debug-level flags are defined when service is bound.
    public static boolean sDebug = false;
    public static boolean sVerbose = false;

    /**
     * Appends {@code value} to the {@code builder} redacting its contents.
     */
    public static void appendRedacted(@NonNull StringBuilder builder,
            @Nullable CharSequence value) {
        builder.append(getRedacted(value));
    }

    /**
     * Gets the redacted version of a value.
     */
    @NonNull
    public static String getRedacted(@Nullable CharSequence value) {
        return (value == null) ? "null" : value.length() + "_chars";
    }

    /**
     * Appends {@code values} to the {@code builder} redacting its contents.
     */
    public static void appendRedacted(@NonNull StringBuilder builder, @Nullable String[] values) {
        if (values == null) {
            builder.append("N/A");
            return;
        }
        builder.append("[");
        for (String value : values) {
            builder.append(" '");
            appendRedacted(builder, value);
            builder.append("'");
        }
        builder.append(" ]");
    }

    /**
     * Converts a collaction of {@link AutofillId AutofillIds} to an array.
     *
     * @param collection The collection.
     * @return The array.
     */
    public static @NonNull AutofillId[] toArray(Collection<AutofillId> collection) {
        if (collection == null) {
            return new AutofillId[0];
        }
        final AutofillId[] array = new AutofillId[collection.size()];
        collection.toArray(array);
        return array;
    }

    /**
     * Converts a Set to a List.
     */
    @Nullable
    public static <T> ArrayList<T> toList(@Nullable Set<T> set) {
        return set == null ? null : new ArrayList<T>(set);
    }

    private Helper() {
        throw new UnsupportedOperationException("contains static members only");
    }
}
