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

import java.util.Set;

/** @hide */
public final class Helper {

    // TODO(b/33197203): set to false when stable
    static final boolean DEBUG = true;
    static final String REDACTED = "[REDACTED]";

    static void append(StringBuilder builder, Bundle bundle) {
        if (bundle == null) {
            builder.append("N/A");
        } else if (!DEBUG) {
            builder.append(REDACTED);
        } else {
            final Set<String> keySet = bundle.keySet();
            builder.append("[bundle with ").append(keySet.size()).append(" extras:");
            for (String key : keySet) {
                builder.append(' ').append(key).append('=').append(bundle.get(key)).append(',');
            }
            builder.append(']');
        }
    }

    private Helper() {
        throw new UnsupportedOperationException("contains static members only");
    }
}
