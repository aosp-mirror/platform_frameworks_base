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

import android.os.Bundle;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** @hide */
public final class Helper {

    // Debug-level flags are defined when service is bound.
    public static boolean sDebug = false;
    public static boolean sVerbose = false;

    public static final String REDACTED = "[REDACTED]";

    static StringBuilder append(StringBuilder builder, Bundle bundle) {
        if (bundle == null || !sDebug) {
            builder.append("N/A");
        } else if (!sVerbose) {
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
        return builder;
    }

    private Helper() {
        throw new UnsupportedOperationException("contains static members only");
    }
}
