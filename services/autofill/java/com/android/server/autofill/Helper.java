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

import android.annotation.Nullable;
import android.os.Bundle;
import android.util.ArraySet;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

final class Helper {

    static final boolean DEBUG = true; // TODO(b/33197203): set to false when stable
    static final boolean VERBOSE = false;
    static final String REDACTED = "[REDACTED]";

    static void append(StringBuilder builder, Bundle bundle) {
        if (bundle == null) {
            builder.append("N/A");
        } else if (!VERBOSE) {
            builder.append(REDACTED);
        } else {
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
    }

    static String bundleToString(Bundle bundle) {
        final StringBuilder builder = new StringBuilder();
        append(builder, bundle);
        return builder.toString();
    }

    /**
     * Gets the value of a {@link Dataset} field by its id, or {@code null} if not found.
     */
    @Nullable
    static AutoFillValue findValue(Dataset dataset, AutoFillId id) {
        if (dataset != null) {
            final ArrayList<AutoFillId> ids = dataset.getFieldIds();
            final int size = ids.size();
            for (int i = 0; i < size; i++) {
                if (id.equals(ids.get(i))) {
                    return dataset.getFieldValues().get(i);
                }

            }
        }
        return null;
    }

    /**
     * Finds a data set by id in a response.
     *
     * @param id The dataset id.
     * @param response The response to search.
     * @return The dataset if found or null.
     */
    static Dataset findDatasetById(String id, FillResponse response) {
        ArraySet<Dataset> datasets = response.getDatasets();
        if (datasets == null || datasets.isEmpty()) {
            return null;
        }
        final int datasetCount = datasets.size();
        for (int i = 0; i < datasetCount; i++) {
            Dataset dataset = datasets.valueAt(i);
            if (dataset.getId().equals(id)) {
                return dataset;
            }
        }
        return null;
    }

    private Helper() {
        throw new UnsupportedOperationException("contains static members only");
    }
}
