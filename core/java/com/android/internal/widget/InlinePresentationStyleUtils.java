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

package com.android.internal.widget;

import android.annotation.NonNull;
import android.os.Bundle;

import java.util.Objects;
import java.util.Set;

/**
 * Utility methods relating to inline presentation UI.
 */
public final class InlinePresentationStyleUtils {

    /**
     * Returns true if the two bundles are deeply equal.
     *
     * Each input bundle may represent a UI style in the
     * {@link android.widget.inline.InlinePresentationSpec} or the extra
     * request info in the {@link android.view.inputmethod.InlineSuggestionsRequest}
     *
     * Note: this method should not be called in the framework process for security reasons.
     */
    public static boolean bundleEquals(@NonNull Bundle bundle1, @NonNull Bundle bundle2) {
        if (bundle1 == bundle2) {
            return true;
        }
        if (bundle1 == null || bundle2 == null) {
            return false;
        }
        if (bundle1.size() != bundle2.size()) {
            return false;
        }
        Set<String> keys = bundle1.keySet();
        for (String key : keys) {
            Object value1 = bundle1.get(key);
            Object value2 = bundle2.get(key);
            if (value1 instanceof Bundle && value2 instanceof Bundle
                    && !bundleEquals((Bundle) value1, (Bundle) value2)) {
                return false;
            } else if (!Objects.equals(value1, value2)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Private ctor to avoid constructing the class.
     */
    private InlinePresentationStyleUtils() {
    }
}
