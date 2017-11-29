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

package com.android.server.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

public final class Helper {

    /**
     * Defines a logging flag that can be dynamically changed at runtime using
     * {@code cmd autofill set log_level debug}.
     */
    public static boolean sDebug = false;

    /**
     * Defines a logging flag that can be dynamically changed at runtime using
     * {@code cmd autofill set log_level verbose}.
     */
    public static boolean sVerbose = false;

    /**
     * Maximum number of partitions that can be allowed in a session.
     *
     * <p>Can be modified using {@code cmd autofill set max_partitions}.
     */
    static int sPartitionMaxCount = 10;

    private Helper() {
        throw new UnsupportedOperationException("contains static members only");
    }

    static void append(StringBuilder builder, Bundle bundle) {
        if (bundle == null || !sVerbose) {
            builder.append("null");
            return;
        }
        final Set<String> keySet = bundle.keySet();
        builder.append("[Bundle with ").append(keySet.size()).append(" extras:");
        for (String key : keySet) {
            final Object value = bundle.get(key);
            builder.append(' ').append(key).append('=');
            builder.append((value instanceof Object[])
                    ? Arrays.toString((Objects[]) value) : value);
        }
        builder.append(']');
    }

    static String bundleToString(Bundle bundle) {
        final StringBuilder builder = new StringBuilder();
        append(builder, bundle);
        return builder.toString();
    }

    @Nullable
    static AutofillId[] toArray(@Nullable ArraySet<AutofillId> set) {
        if (set == null) return null;

        final AutofillId[] array = new AutofillId[set.size()];
        for (int i = 0; i < set.size(); i++) {
            array[i] = set.valueAt(i);
        }
        return array;
    }

    @NonNull
    static ArrayMap<AutofillId, AutofillValue> getFields(@NonNull Dataset dataset) {
        final ArrayList<AutofillId> ids = dataset.getFieldIds();
        final ArrayList<AutofillValue> values = dataset.getFieldValues();
        final int size = ids == null ? 0 : ids.size();
        final ArrayMap<AutofillId, AutofillValue> fields = new ArrayMap<>(size);
        for (int i = 0; i < size; i++) {
            fields.put(ids.get(i), values.get(i));
        }
        return fields;
    }

    @NonNull
    public static LogMaker newLogMaker(int category, String packageName,
            String servicePackageName) {
        final LogMaker log = new LogMaker(category).setPackageName(packageName);
        if (servicePackageName != null) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName);
        }
        return log;
    }
}
